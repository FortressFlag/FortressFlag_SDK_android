package com.fortressflag.sdk

import com.fortressflag.sdk.cache.CachedEnvelope
import com.fortressflag.sdk.cache.EnvelopeCache
import com.fortressflag.sdk.evaluation.Resolver
import com.fortressflag.sdk.support.Log
import com.fortressflag.sdk.transport.Backoff
import com.fortressflag.sdk.transport.ClientApi
import com.fortressflag.sdk.transport.EnvelopeFixture
import com.fortressflag.sdk.transport.FetchOutcome
import com.fortressflag.sdk.transport.TransportFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The suite that proves the one thing the SDK actually promises: **flagging can fail in any
 * way at all, and the host app neither crashes nor sees an error.**
 *
 * Every other test checks that a specific thing works. These check that nothing breaks when
 * everything is wrong at once — which is the state a real device is in during an outage, on a
 * hotel Wi-Fi captive portal, or in the hands of someone actively attacking us. Ported from
 * the iOS SDK's ChaosTests; the signature-hostile entries differ only in that Android's
 * verification primitive is the fail-closed stub (ADR-0013), so "signed by an attacker" and
 * "signed correctly" are equally rejected under a Required policy — which is the safe half of
 * the iOS matrix, and the half that exists before backend M4.
 */
class ChaosTest {
    private val now = EnvelopeFixture.NOW

    /** Every way a response can be wrong, in one list. */
    private fun hostileResponses(): List<FetchOutcome> {
        fun envelope(payload: String) = FetchOutcome.Success(EnvelopeFixture.envelope(payload), null)

        return listOf(
            // Transport-level failures
            FetchOutcome.Failure(TransportFailure.Offline),
            FetchOutcome.Failure(TransportFailure.TimedOut),
            FetchOutcome.Failure(TransportFailure.Cancelled),
            FetchOutcome.Failure(TransportFailure.Unauthorized),
            FetchOutcome.Failure(TransportFailure.RateLimited(null)),
            FetchOutcome.Failure(TransportFailure.RateLimited(31_536_000.0)),
            FetchOutcome.Failure(TransportFailure.ServerError(500)),
            FetchOutcome.Failure(TransportFailure.ServerError(503)),
            FetchOutcome.Failure(TransportFailure.UnexpectedStatus(418)),
            FetchOutcome.Failure(TransportFailure.ResponseTooLarge),
            FetchOutcome.Failure(TransportFailure.Other("something nobody anticipated")),
            // Bodies that are not envelopes at all
            FetchOutcome.Success(ByteArray(0), null),
            FetchOutcome.Success("<html>captive portal</html>".toByteArray(), null),
            FetchOutcome.Success("{".toByteArray(), null),
            FetchOutcome.Success("null".toByteArray(), null),
            FetchOutcome.Success(ByteArray(4096), null),
            // Envelopes that are structurally valid but must not be trusted
            envelope(EnvelopeFixture.payloadJson(environment = "prod")),
            envelope(EnvelopeFixture.payloadJson(device = "dev_BBBBBBBBBBBBBBBBBBAAAA")),
            envelope(EnvelopeFixture.payloadJson(version = 99)),
            envelope(
                EnvelopeFixture.payloadJson(
                    issuedAt = "2026-08-18T08:00:00Z",
                    expiresAt = "2026-08-18T08:30:00Z",
                ),
            ),
            envelope(EnvelopeFixture.payloadJson(issuedAt = "2026-08-19T10:00:00Z")),
            // Value-union violations: null, object, array
            envelope(EnvelopeFixture.payloadJson(flags = """{"alpha":null}""")),
            envelope(EnvelopeFixture.payloadJson(flags = """{"alpha":{"nested":true}}""")),
            envelope(EnvelopeFixture.payloadJson(flags = """{"alpha":[1,2]}""")),
            // Truncation, the classic mid-flight failure
            FetchOutcome.Success(EnvelopeFixture.envelope().copyOf(20), null),
            // A 304 with nothing necessarily cached behind it
            FetchOutcome.NotModified,
        )
    }

    private class SeededCache(
        seed: ByteArray? = null,
    ) : EnvelopeCache {
        var contents: ByteArray? = seed
        var stores = 0

        override fun load(): CachedEnvelope? = contents?.let { CachedEnvelope(it, null) }

        override fun store(
            raw: ByteArray,
            etag: String?,
        ) {
            contents = raw
            stores += 1
        }

        override fun clear() {
            contents = null
        }
    }

    private class ScriptedApi(
        private val script: List<FetchOutcome>,
    ) : ClientApi {
        var index = 0

        override suspend fun fetch(
            deviceId: String,
            etag: String?,
            tags: String?,
        ): FetchOutcome {
            val outcome = script[index % script.size]
            index += 1
            return outcome
        }
    }

    private class StubIdentity(
        private val value: String?,
    ) : FlagClient.IdentitySource {
        override fun identity(): String? = value

        override fun peekStored(): String? = value

        override fun reset() {}
    }

    private fun client(
        api: ClientApi,
        cache: EnvelopeCache,
        store: SnapshotStore,
        identity: FlagClient.IdentitySource = StubIdentity(EnvelopeFixture.DEVICE),
        notify: (Set<String>) -> Unit = {},
    ) = FlagClient(
        configuration =
            Configuration(
                sdkKey = "ffc_dev_k",
                environment = Environment.DEVELOPMENT,
                signaturePolicy = SignaturePolicy.Disabled,
            ),
        identity = identity,
        api = api,
        cache = cache,
        store = store,
        log = Log(LogPolicy.SILENT, "chaos"),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        builtinTags = emptyMap(),
        backoff = Backoff(),
        now = { now },
        random = { _, _ -> 0.0 },
        notify = notify,
    )

    @Test
    fun aDeviceHoldingAGoodValueNeverLosesIt() {
        val good =
            EnvelopeFixture.envelope(
                EnvelopeFixture.payloadJson(flags = """{"alpha":true,"beta":false}"""),
            )
        val cache = SeededCache(good)
        val store = SnapshotStore()
        val hostile = hostileResponses()
        val flagClient = client(ScriptedApi(hostile), cache, store)

        flagClient.loadCacheIntoStore()

        runBlocking {
            repeat(hostile.size) {
                // Whatever happened, it was reported as an outcome and not raised.
                val outcome = flagClient.refresh()
                assertTrue(outcome !is RefreshOutcome.NotStarted)

                // And the device still answers correctly, from the value it recorded.
                val snapshot = store.current
                val alpha = Resolver.resolve("alpha", snapshot.fresh, snapshot.cached, null)
                assertEquals(true, alpha.value.boolValue)
                assertEquals(ValueSource.CACHED, alpha.source)
            }
        }

        // The cache bytes are exactly what they were. Nothing hostile rewrote them.
        assertEquals(0, cache.stores)
        assertTrue(cache.contents!!.contentEquals(good))
    }

    @Test
    fun aColdDeviceAnswersFalseForeverWithoutCrashing() {
        val store = SnapshotStore()
        val hostile = hostileResponses()
        val flagClient = client(ScriptedApi(hostile), SeededCache(), store)

        runBlocking {
            repeat(hostile.size) {
                flagClient.refresh()
                val snapshot = store.current
                val result = Resolver.resolve("anything", snapshot.fresh, snapshot.cached, null)
                assertEquals(false, result.value.boolValue)
                assertEquals(ValueSource.SAFE_DEFAULT, result.source)
            }
        }
    }

    @Test
    fun signatureHostileEnvelopesAllRejectUnderRequired() {
        // Under Required with a trust store, every signature shape must reject-to-cache:
        // absent, malformed, unknown key, wrong algorithm — and, until M4 supplies the
        // primitive, even a plausible one (the stub can only reject; it can never accept a
        // forgery).
        val good = EnvelopeFixture.envelope()
        val cache = SeededCache(good)
        val store = SnapshotStore()
        val signedShapes =
            listOf(
                FetchOutcome.Success(EnvelopeFixture.envelope(sig = null), null),
                FetchOutcome.Success(EnvelopeFixture.envelope(sig = "garbage"), null),
                FetchOutcome.Success(EnvelopeFixture.envelope(sig = "ed25519:unknown-key:AAAA"), null),
                FetchOutcome.Success(EnvelopeFixture.envelope(sig = "p256:k1:AAAA"), null),
                FetchOutcome.Success(EnvelopeFixture.envelope(sig = "ed25519:k1:AAAA"), null),
            )
        val flagClient =
            FlagClient(
                configuration =
                    Configuration(
                        sdkKey = "ffc_dev_k",
                        environment = Environment.DEVELOPMENT,
                        signaturePolicy = SignaturePolicy.Required(TrustedKeys(mapOf("k1" to ByteArray(32)))),
                    ),
                identity = StubIdentity(EnvelopeFixture.DEVICE),
                api = ScriptedApi(signedShapes),
                cache = cache,
                store = store,
                log = Log(LogPolicy.SILENT, "chaos"),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                builtinTags = emptyMap(),
                now = { now },
                random = { _, _ -> 0.0 },
                notify = {},
            )
        flagClient.loadCacheIntoStore()

        runBlocking {
            repeat(signedShapes.size) {
                val outcome = flagClient.refresh()
                assertTrue(outcome is RefreshOutcome.Failed)
                assertEquals(RefreshFailure.REJECTED_PAYLOAD, (outcome as RefreshOutcome.Failed).failure)
            }
        }
        assertEquals(0, cache.stores)
    }

    @Test
    fun identityStorageUnavailableDegradesItDoesNotFail() {
        val store = SnapshotStore()
        val cache = SeededCache(EnvelopeFixture.envelope())
        val flagClient =
            client(
                ScriptedApi(listOf(FetchOutcome.Failure(TransportFailure.Offline))),
                cache,
                store,
                identity = StubIdentity(null),
            )

        // No identity, so the device check is skipped and the cache still serves — the
        // whole point of making the check optional.
        flagClient.loadCacheIntoStore()
        assertEquals(
            true,
            store.current.cached
                ?.get("dark-mode")
                ?.boolValue,
        )

        runBlocking {
            val outcome = flagClient.refresh()
            assertTrue(outcome is RefreshOutcome.Failed)
        }
        assertEquals(
            true,
            store.current.cached
                ?.get("dark-mode")
                ?.boolValue,
        )
    }

    @Test
    fun aHostileChangeHandlerDoesNotTakeTheSdkWithIt() {
        // Customers write these handlers. One that recurses into the SDK, blocks, or throws
        // must not deadlock or crash the app — the broadcast copies handlers out from under
        // its lock and swallows their exceptions.
        val store = SnapshotStore()
        var calls = 0
        val flagClient =
            client(
                ScriptedApi(listOf(FetchOutcome.Success(EnvelopeFixture.envelope(), null))),
                SeededCache(),
                store,
                notify = {
                    calls += 1
                    // Re-entrant read from inside the handler.
                    store.current
                    throw IllegalStateException("hostile handler")
                },
            )

        runBlocking {
            val outcome =
                try {
                    flagClient.refresh()
                } catch (e: IllegalStateException) {
                    error("the handler's exception escaped to the caller")
                }
            assertTrue(outcome is RefreshOutcome.Updated)
        }
        assertEquals(1, calls)
    }
}
