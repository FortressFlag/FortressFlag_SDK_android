package com.fortressflag.sdk

import com.fortressflag.sdk.cache.EnvelopeCache
import com.fortressflag.sdk.evaluation.Resolver
import com.fortressflag.sdk.support.Log
import com.fortressflag.sdk.tags.BuiltinTags
import com.fortressflag.sdk.tags.Tags
import com.fortressflag.sdk.transport.Backoff
import com.fortressflag.sdk.transport.ClientApi
import com.fortressflag.sdk.transport.EnvelopeVerifier
import com.fortressflag.sdk.transport.FetchOutcome
import com.fortressflag.sdk.transport.TransportFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Orchestrates identity, transport and cache, and publishes results into the
 * [SnapshotStore]. Mutable state is guarded by [stateMutex]; the synchronous read path never
 * touches this class — it reads the store directly.
 */
internal class FlagClient(
    private val configuration: Configuration,
    private val identity: IdentitySource,
    private val api: ClientApi,
    private val cache: EnvelopeCache,
    private val store: SnapshotStore,
    private val log: Log,
    private val scope: CoroutineScope,
    private val builtinTags: Map<String, String>,
    private val backoff: Backoff = Backoff(),
    private val now: () -> Instant = Instant::now,
    private val random: (Double, Double) -> Double = Backoff.SYSTEM_RANDOM,
    private val notify: (Set<String>) -> Unit,
) {
    /** The identity dependency, injectable so chaos tests can run without a Keystore. */
    internal interface IdentitySource {
        /** The stored-or-minted identity, or null when identity storage is unavailable. */
        fun identity(): String?

        /** The stored identity WITHOUT minting, or null. Used by the synchronous cache
         * load, which must not pay Keystore key-generation cost on the caller's thread. */
        fun peekStored(): String?

        fun reset()
    }

    private val stateMutex = Mutex()
    private var etag: String? = null
    private var inFlight: CompletableDeferred<RefreshOutcome>? = null
    private var pollJob: Job? = null
    private var consecutiveFailures = 0
    private var lastRetryAfterSeconds: Double? = null
    private var customTags: Map<String, String>
    private var encodedTags: String?

    init {
        customTags = Tags.sanitize(configuration.tags, BuiltinTags.RESERVED_KEYS, log)
        encodedTags = Tags.encode(builtinTags, customTags, log)
    }

    // MARK: lifecycle

    /**
     * Begins polling. [restoredEtag] comes from the synchronous cache load the facade
     * already performed — see [loadCacheIntoStore].
     */
    fun start(restoredEtag: String?) {
        scope.launch {
            stateMutex.withLock {
                etag = restoredEtag
                publishTagKeysLocked()
                pollJob?.cancel()
                pollJob = scope.launch { pollLoop() }
            }
            // Resolved here rather than on the caller's thread: minting touches the
            // Keystore, which can block, and app launch must not pay for it.
            identity.identity()?.let { id -> store.update { it.copy(deviceId = id) } }
        }
    }

    suspend fun stop() {
        stateMutex.withLock {
            pollJob?.cancel()
            pollJob = null
            inFlight?.cancel()
            inFlight = null
        }
    }

    suspend fun resetIdentity() {
        stateMutex.withLock {
            identity.reset()
            cache.clear()
            etag = null
            consecutiveFailures = 0
        }
        store.update { it.copy(fresh = null, cached = null, deviceId = null) }
        log.debug("identity and cached values cleared")
    }

    // MARK: tags

    /**
     * Replaces the custom tag set. The caller follows with one coalesced [refresh], so a
     * login-driven tag takes effect within a request rather than a poll.
     */
    suspend fun setTags(tags: Map<String, String>) {
        stateMutex.withLock {
            customTags = Tags.sanitize(tags, BuiltinTags.RESERVED_KEYS, log)
            val encoded = Tags.encode(builtinTags, customTags, log)
            if (encoded != encodedTags) {
                // A different tag set can mean a different payload for this same device, so
                // the stored validator no longer names what the next response would be.
                etag = null
            }
            encodedTags = encoded
            publishTagKeysLocked()
            log.debug("custom tags replaced (${customTags.size} tag(s) kept)")
        }
    }

    /** Publishes the KEYS of what a fetch will send, for the diagnostics screen. */
    private fun publishTagKeysLocked() {
        val keys = (customTags.keys + builtinTags.keys).sorted()
        store.update { it.copy(sentTagKeys = keys) }
    }

    // MARK: refresh

    /**
     * Fetches new values, coalescing concurrent callers onto one request. Coalescing is not
     * an optimisation: without it, an app calling refresh() from several screens on
     * foreground would issue several identical requests, and the SDK would be the reason the
     * customer hit their own rate limit.
     */
    suspend fun refresh(): RefreshOutcome {
        val (deferred, isOwner) =
            stateMutex.withLock {
                inFlight?.let { return@withLock it to false }
                val fresh = CompletableDeferred<RefreshOutcome>()
                inFlight = fresh
                fresh to true
            }
        if (!isOwner) {
            return try {
                deferred.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The shared request was cancelled (stop()), not this caller: a failed
                // refresh, never an exception — unless the CALLER's own coroutine is the
                // cancelled one, in which case cancellation must propagate normally.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                RefreshOutcome.Failed(RefreshFailure.NETWORK)
            }
        }

        val outcome =
            try {
                performRefresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                deferred.complete(RefreshOutcome.Failed(RefreshFailure.NETWORK))
                stateMutex.withLock { if (inFlight === deferred) inFlight = null }
                throw e
            } catch (e: Exception) {
                // Nothing may escape to a caller (Founding §8.1); an unexpected failure is a
                // failed refresh, and the cache keeps answering.
                log.error("refresh failed unexpectedly: ${e::class.simpleName}")
                RefreshOutcome.Failed(RefreshFailure.NETWORK)
            }
        deferred.complete(outcome)
        stateMutex.withLock { if (inFlight === deferred) inFlight = null }
        return outcome
    }

    private suspend fun performRefresh(): RefreshOutcome {
        val deviceId = identity.identity()
        if (deviceId == null) {
            log.debug("no device identity available")
            stateMutex.withLock { recordFailureLocked(null) }
            return RefreshOutcome.Failed(RefreshFailure.NETWORK)
        }
        store.update { it.copy(deviceId = deviceId) }

        val (sendEtag, sendTags) = stateMutex.withLock { etag to encodedTags }
        return when (val outcome = api.fetch(deviceId, sendEtag, sendTags)) {
            is FetchOutcome.NotModified -> {
                // A 304 is a successful conversation with the server, so it counts as a
                // fetch: leaving lastSuccessfulFetch stale would make a healthy device whose
                // flags simply have not changed look, on a diagnostics screen, exactly like
                // one that has been failing for a week.
                stateMutex.withLock { recordSuccessLocked() }
                store.update { it.copy(lastSuccessfulFetchEpochMillis = now().toEpochMilli()) }
                RefreshOutcome.Unchanged
            }
            is FetchOutcome.Failure -> {
                log.debug("flag fetch failed: ${outcome.failure::class.simpleName}")
                val retryAfter = (outcome.failure as? TransportFailure.RateLimited)?.retryAfterSeconds
                stateMutex.withLock { recordFailureLocked(retryAfter) }
                RefreshOutcome.Failed(classify(outcome.failure))
            }
            is FetchOutcome.Success -> accept(outcome.raw, outcome.etag, deviceId)
        }
    }

    /**
     * Verifies a freshly-fetched payload and, only if it passes, promotes it to both the
     * live snapshot and the durable cache.
     *
     * A rejected payload leaves the cache untouched. That ordering is the point: an attacker
     * who can serve responses cannot erase what the device already knows — they can only
     * fail to change it.
     */
    private suspend fun accept(
        raw: ByteArray,
        responseEtag: String?,
        deviceId: String,
    ): RefreshOutcome {
        val expectations =
            EnvelopeVerifier.Expectations(
                environment = configuration.environment,
                deviceId = deviceId,
                now = now(),
            )
        return when (val result = EnvelopeVerifier.verify(raw, configuration.signaturePolicy, expectations)) {
            is EnvelopeVerifier.Result.Rejected -> {
                log.error("rejected a flag payload: ${result.rejection}. Serving the last known values.")
                stateMutex.withLock { recordFailureLocked(null) }
                RefreshOutcome.Failed(RefreshFailure.REJECTED_PAYLOAD)
            }
            is EnvelopeVerifier.Result.Accepted -> {
                val payloadFlags = result.envelope.payload.flags
                val previous = store.current
                val changed = Resolver.changedKeys(previous.fresh, payloadFlags, previous.cached)

                cache.store(raw, responseEtag)
                stateMutex.withLock {
                    etag = responseEtag
                    recordSuccessLocked()
                }
                store.update {
                    // The cache tier mirrors the accepted payload so the two never disagree
                    // within a launch — and so a later rejection falls back to something we
                    // have verified. Wholesale overwrite, both tiers: a key that left the
                    // payload stops resolving from either on this same poll (ADR-0003).
                    it.copy(
                        fresh = payloadFlags,
                        cached = payloadFlags,
                        lastSuccessfulFetchEpochMillis = now().toEpochMilli(),
                    )
                }

                if (changed.isEmpty()) {
                    RefreshOutcome.Unchanged
                } else {
                    log.debug("flag values changed (${changed.size} key(s))")
                    try {
                        notify(changed)
                    } catch (_: Exception) {
                        // A hostile or buggy change handler must not turn a successful
                        // refresh into a failure — or reach the host app.
                    }
                    RefreshOutcome.Updated(changed)
                }
            }
        }
    }

    // MARK: cache

    /**
     * Reads the durable cache and publishes it, returning the stored ETag. Synchronous on
     * purpose: it runs on the caller's thread during [FortressFlag.start], before that call
     * returns, so that an `isEnabled` on the very next line of the host app's launch already
     * sees the last values this device had. Deferring it would mean every cold start briefly
     * answers `false` for every flag — a visible flicker of un-launched features, precisely
     * what the durable cache exists to prevent (Founding §8.4).
     */
    fun loadCacheIntoStore(): String? {
        val cached = cache.load() ?: return null

        // The identity may not be resolvable yet; the verifier skips the device check when
        // null rather than discarding the fallback — see Expectations.deviceId.
        val knownDevice =
            try {
                identity.peekStored()
            } catch (_: Exception) {
                null
            }

        val expectations =
            EnvelopeVerifier.Expectations(
                environment = configuration.environment,
                deviceId = knownDevice,
                now = now(),
                // Expiry is a freshness signal, not a validity one: an offline device must keep
                // serving what it last saw for as long as it stays offline (Founding §8.4).
                enforceExpiry = false,
            )

        return when (val result = EnvelopeVerifier.verify(cached.raw, configuration.signaturePolicy, expectations)) {
            is EnvelopeVerifier.Result.Accepted -> {
                store.update { it.copy(cached = result.envelope.payload.flags) }
                log.debug("restored ${result.envelope.payload.flags.size} cached flag value(s)")
                cached.etag
            }
            is EnvelopeVerifier.Result.Rejected -> {
                // A cache we cannot verify is a cache we cannot use. Removing it stops us
                // re-reading and re-rejecting the same bytes on every launch, and an
                // unverifiable file is exactly what a poisoning attempt looks like.
                log.warning("discarding an unverifiable flag cache: ${result.rejection}")
                cache.clear()
                null
            }
        }
    }

    // MARK: polling

    private suspend fun pollLoop() {
        while (scope.isActive) {
            refresh()
            val (failures, retryAfter) = stateMutex.withLock { consecutiveFailures to lastRetryAfterSeconds }
            val delaySeconds =
                if (failures > 0) {
                    backoff.retryDelaySeconds(retryAfter, failures, random)
                } else {
                    backoff.pollDelaySeconds(configuration.refreshIntervalSeconds.toDouble(), random)
                }
            delay((delaySeconds * 1000).toLong().coerceAtLeast(0))
        }
    }

    private fun recordSuccessLocked() {
        consecutiveFailures = 0
        lastRetryAfterSeconds = null
    }

    private fun recordFailureLocked(retryAfterSeconds: Double?) {
        // Saturating rather than wrapping: a device offline for a very long time keeps the
        // cap, it does not wrap around to retrying every two seconds.
        if (consecutiveFailures < Int.MAX_VALUE - 1) consecutiveFailures += 1
        lastRetryAfterSeconds = retryAfterSeconds
    }

    private fun classify(failure: TransportFailure): RefreshFailure =
        when (failure) {
            is TransportFailure.Unauthorized -> RefreshFailure.UNAUTHORIZED
            is TransportFailure.RateLimited -> RefreshFailure.RATE_LIMITED
            is TransportFailure.ServerError,
            is TransportFailure.UnexpectedStatus,
            is TransportFailure.ResponseTooLarge,
            -> RefreshFailure.SERVER
            else -> RefreshFailure.NETWORK
        }
}
