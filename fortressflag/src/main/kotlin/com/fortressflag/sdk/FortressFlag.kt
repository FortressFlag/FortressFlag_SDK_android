package com.fortressflag.sdk

import android.content.Context
import com.fortressflag.sdk.cache.FileEnvelopeCache
import com.fortressflag.sdk.evaluation.Resolver
import com.fortressflag.sdk.identity.DeviceIdentityStore
import com.fortressflag.sdk.support.Log
import com.fortressflag.sdk.tags.BuiltinTags
import com.fortressflag.sdk.transport.Backoff
import com.fortressflag.sdk.transport.ClientApi
import com.fortressflag.sdk.transport.HttpClientApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * FortressFlag's Android client SDK.
 *
 * ## The promise
 *
 * **No call in this API throws or can crash your app.** A FortressFlag outage, a dead
 * network, a revoked key, a hostile Wi-Fi portal, a corrupt cache — all of them resolve to a
 * flag value and none of them reach your code as an error. That is the product: if flagging
 * can take an app down, it is worse than no flagging at all (Founding §8.1).
 *
 * ## What this is not
 *
 * **Flag values are not a security boundary.** They are evaluated on a device the end user
 * controls; anyone willing to patch your binary can turn any flag on. Use flags to decide
 * what to show, never to decide what someone is entitled to.
 *
 * ## Usage
 *
 * ```kotlin
 * FortressFlag.start(context, Configuration(sdkKey = "ffc_prod_…", environment = Environment.PRODUCTION))
 * if (FortressFlag.isEnabled("new-checkout")) { … }
 * ```
 */
public object FortressFlag {
    private val lock = Any()
    private val snapshots = SnapshotStore()
    private var client: FlagClient? = null
    private var scope: CoroutineScope? = null
    private val listeners = LinkedHashMap<Long, (Set<String>) -> Unit>()
    private val nextListenerId = AtomicLong(0)

    // MARK: lifecycle

    /**
     * Starts the SDK. Safe to call from `Application.onCreate`.
     *
     * Returns as soon as the durable cache has been read, so a flag read on the next line
     * already sees the last values this device had. Everything else — identity, network,
     * polling — happens in the background. Calling it a second time replaces the
     * configuration and discards in-memory state. Invalid configuration is logged, never
     * fatal: an app that ships with a typo'd key still launches, it just serves defaults.
     */
    public fun start(
        context: Context,
        configuration: Configuration,
    ) {
        val appContext = context.applicationContext
        val log = Log(configuration.logging, "client")
        for (problem in configuration.validate()) {
            log.warning("configuration problem — $problem")
        }

        val identityStore = DeviceIdentityStore(appContext)
        val identity =
            object : FlagClient.IdentitySource {
                override fun identity(): String? =
                    try {
                        identityStore.currentOrMint()
                    } catch (_: Exception) {
                        null
                    }

                override fun peekStored(): String? = identityStore.peek()

                override fun reset() {
                    identityStore.reset()
                }
            }
        val api = HttpClientApi(configuration, Log(configuration.logging, "transport"))
        val cache =
            FileEnvelopeCache(
                appContext,
                configuration.sdkKey,
                configuration.environment,
                Log(configuration.logging, "cache"),
            )

        start(configuration, identity, api, cache, log, BuiltinTags.current(appContext))
    }

    /** Dependency-injected start, used by the test suite. */
    internal fun start(
        configuration: Configuration,
        identity: FlagClient.IdentitySource,
        api: ClientApi,
        cache: com.fortressflag.sdk.cache.EnvelopeCache,
        log: Log,
        builtinTags: Map<String, String> = emptyMap(),
        backoff: Backoff = Backoff(),
        now: () -> Instant = Instant::now,
        random: (Double, Double) -> Double = Backoff.SYSTEM_RANDOM,
    ) {
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val newClient =
            FlagClient(
                configuration = configuration,
                identity = identity,
                api = api,
                cache = cache,
                store = snapshots,
                log = log,
                scope = newScope,
                builtinTags = builtinTags,
                backoff = backoff,
                now = now,
                random = random,
                notify = { changed -> broadcast(changed) },
            )

        val previousScope: CoroutineScope?
        synchronized(lock) {
            previousScope = scope
            snapshots.reset()
            client = newClient
            scope = newScope
        }
        previousScope?.cancel()

        // Synchronous, before this function returns — see FlagClient.loadCacheIntoStore.
        val restoredEtag = newClient.loadCacheIntoStore()

        // Marked started here rather than inside the client, so diagnostics.isStarted is
        // true the instant start returns.
        snapshots.update { it.copy(isStarted = true) }

        newClient.start(restoredEtag)
    }

    /** Stops polling. Values already resolved keep resolving from memory and cache. Never
     * blocks: cancelling the SDK's own scope tears down the poll loop and any in-flight
     * fetch. */
    public fun stop() {
        val stoppedScope =
            synchronized(lock) {
                val s = scope
                client = null
                scope = null
                s
            }
        snapshots.update { it.copy(isStarted = false) }
        stoppedScope?.cancel()
    }

    // MARK: reading flags

    /**
     * Whether [key] is on for this device. Synchronous and allocation-light: it reads an
     * in-memory snapshot — no I/O, no suspension, safe to call from a render pass.
     *
     * The resolution order is fixed (Founding §8.4):
     * 1. the most recent value fetched from FortressFlag
     * 2. the last value this device recorded, from the durable cache
     * 3. [default], if you supplied one
     * 4. `false`
     *
     * Note step 2 before step 3: a value this device actually received always beats a
     * compiled-in default, however old it is. [default] answers "what if this device has
     * never heard anything about this flag at all?" — not "what if we are offline?".
     *
     * The kind projection (contract v2): a string or number flag read through this boolean
     * API resolves to the caller's default, else `false` — fail-safe, never a coercion,
     * never an error.
     */
    @JvmStatic
    @JvmOverloads
    public fun isEnabled(
        key: String,
        default: Boolean? = null,
    ): Boolean {
        val resolution = resolve(key, default)
        return resolution.value.boolValue ?: default ?: false
    }

    /**
     * The value of a STRING flag, or [default] when the flag is unknown, has no served value
     * yet, or is not a string kind. Never throws; the cascade is [isEnabled]'s exactly.
     */
    @JvmStatic
    public fun stringValue(
        key: String,
        default: String,
    ): String {
        val snapshot = snapshots.current
        return Resolver
            .resolve(key, snapshot.fresh, snapshot.cached, FlagValue.Str(default))
            .value.stringValue ?: default
    }

    /** The value of a NUMBER flag, or [default] under exactly [stringValue]'s rules.
     * Numbers are float64 on the wire, as the server serves them. */
    @JvmStatic
    public fun numberValue(
        key: String,
        default: Double,
    ): Double {
        val snapshot = snapshots.current
        return Resolver
            .resolve(key, snapshot.fresh, snapshot.cached, FlagValue.Num(default))
            .value.numberValue ?: default
    }

    /** As [isEnabled], but reports the value union and where it came from. For diagnostics
     * and for tests that want to assert on more than the projection. */
    @JvmStatic
    @JvmOverloads
    public fun resolve(
        key: String,
        default: Boolean? = null,
    ): Resolution {
        val snapshot = snapshots.current
        return Resolver.resolve(key, snapshot.fresh, snapshot.cached, default?.let { FlagValue.Bool(it) })
    }

    /**
     * Every flag this device has received, with each key's effective value and provenance —
     * the union of the most recent payload and the durable cache, resolved through exactly
     * the same cascade as [isEnabled]. Sources are therefore always FRESH or CACHED: a key
     * the device has never heard of is not in the map at all.
     *
     * This enumerates only this device's own payload — flag keys the server already sends
     * this device, which ship in the app binary in any case. It exposes no other device's
     * data and nothing the management API holds.
     */
    @JvmStatic
    public fun allFlags(): Map<String, Resolution> {
        val snapshot = snapshots.current
        return Resolver.resolveAll(snapshot.fresh, snapshot.cached)
    }

    /**
     * Fetches values now, in addition to the background poll. Never throws; concurrent
     * calls share one request. A good moment to call this is on app foreground; a bad one
     * is in a loop.
     */
    public suspend fun refresh(): RefreshOutcome {
        val current = synchronized(lock) { client } ?: return RefreshOutcome.NotStarted
        return current.refresh()
    }

    /**
     * Replaces the custom tag set and fetches with it immediately. Never throws, never
     * blocks. The replacement is whole-set: tags you omit stop being sent, and the built-in
     * tags are always sent and cannot be overridden. Until the refresh answers, flags keep
     * resolving from the current values — a tag change is a reason to re-ask, not a reason
     * to forget.
     */
    @JvmStatic
    public fun setTags(tags: Map<String, String>) {
        val (currentClient, currentScope) = synchronized(lock) { client to scope }
        if (currentClient == null || currentScope == null) return
        currentScope.launch {
            currentClient.setTags(tags)
            currentClient.refresh()
        }
    }

    // MARK: change notification

    /**
     * Registers a handler for flag changes, returning a token to unregister with. The
     * handler is called with the keys whose *effective* value changed — safe to drive UI
     * invalidation from directly. It runs on a background thread; hop yourself if you touch
     * UI.
     */
    @JvmStatic
    public fun onChange(handler: (Set<String>) -> Unit): ObserverToken {
        val id = nextListenerId.incrementAndGet()
        synchronized(lock) { listeners[id] = handler }
        return ObserverToken { synchronized(lock) { listeners.remove(id) } }
    }

    // MARK: privacy

    /**
     * Deletes this device's identity and every cached value, then mints a fresh identity on
     * next use. A real erasure path, not a flag (Founding §7.3). The old identifier is
     * unrecoverable afterwards — which is the point, and also means this device will be
     * counted as a new one for billing.
     */
    @JvmStatic
    public fun resetIdentity() {
        snapshots.update { it.copy(fresh = null, cached = null, deviceId = null) }
        val (currentClient, currentScope) = synchronized(lock) { client to scope }
        if (currentClient == null || currentScope == null) return
        currentScope.launch { currentClient.resetIdentity() }
    }

    /** A snapshot of what the SDK is doing. Cheap; safe to poll from a debug screen. */
    @JvmStatic
    public val diagnostics: Diagnostics
        get() {
            val snapshot = snapshots.current
            return Diagnostics(
                isStarted = snapshot.isStarted,
                deviceIdentity = snapshot.deviceId,
                lastSuccessfulFetchEpochMillis = snapshot.lastSuccessfulFetchEpochMillis,
                freshFlagCount = snapshot.fresh?.size ?: 0,
                cachedFlagCount = snapshot.cached?.size ?: 0,
                sentTagKeys = snapshot.sentTagKeys,
            )
        }

    /** Copies the handlers out before calling any of them: a handler that registers or
     * removes another handler would otherwise re-enter the lock. */
    private fun broadcast(changed: Set<String>) {
        val handlers = synchronized(lock) { listeners.values.toList() }
        for (handler in handlers) {
            try {
                handler(changed)
            } catch (_: Exception) {
                // A listener throwing must not take down the notification fan-out — or the
                // host app.
            }
        }
    }
}

/** Keeps a change handler registered until [invalidate] is called. */
public class ObserverToken internal constructor(
    private val cancel: () -> Unit,
) {
    /** Unregisters the handler. Safe to call more than once. */
    public fun invalidate(): Unit = cancel()
}
