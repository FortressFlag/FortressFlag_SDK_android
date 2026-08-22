package com.fortressflag.sdk

import com.fortressflag.sdk.cache.CachedEnvelope
import com.fortressflag.sdk.cache.EnvelopeCache
import com.fortressflag.sdk.support.Log
import com.fortressflag.sdk.transport.ClientApi
import com.fortressflag.sdk.transport.EnvelopeFixture
import com.fortressflag.sdk.transport.FetchOutcome
import com.fortressflag.sdk.transport.TransportFailure
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/** In-memory fakes for the seams the facade injects. */
private class FakeCache : EnvelopeCache {
    var stored: CachedEnvelope? = null
    var storeCount = 0

    override fun load(): CachedEnvelope? = stored

    override fun store(
        raw: ByteArray,
        etag: String?,
    ) {
        stored = CachedEnvelope(raw, etag)
        storeCount += 1
    }

    override fun clear() {
        stored = null
    }
}

private class FakeIdentity : FlagClient.IdentitySource {
    var current: String? = EnvelopeFixture.DEVICE

    override fun identity(): String? = current

    override fun peekStored(): String? = current

    override fun reset() {
        current = "dev_BBBBBBBBBBBBBBBBBBAAAA"
    }
}

/** Scripted transport: each call takes the next outcome; the last repeats. */
private class ScriptedApi(
    vararg outcomes: FetchOutcome,
) : ClientApi {
    private val script = outcomes.toMutableList()
    val calls = ConcurrentLinkedQueue<Pair<String?, String?>>() // (etag, tags)

    override suspend fun fetch(
        deviceId: String,
        etag: String?,
        tags: String?,
    ): FetchOutcome {
        calls.add(etag to tags)
        return if (script.size > 1) script.removeAt(0) else script.first()
    }
}

class PublicApiTest {
    private val log = Log(LogPolicy.SILENT, "test")

    private fun config() =
        Configuration(
            sdkKey = "ffc_dev_k",
            environment = Environment.DEVELOPMENT,
            signaturePolicy = SignaturePolicy.Disabled,
        )

    private fun startWith(
        api: ClientApi,
        cache: EnvelopeCache = FakeCache(),
        identity: FakeIdentity = FakeIdentity(),
    ) {
        FortressFlag.start(
            configuration = config(),
            identity = identity,
            api = api,
            cache = cache,
            log = log,
            now = { EnvelopeFixture.NOW },
        )
    }

    @After
    fun tearDown() {
        FortressFlag.stop()
    }

    private fun awaitFetch(
        api: ScriptedApi,
        count: Int = 1,
    ) {
        val deadline = System.currentTimeMillis() + 5000
        while (api.calls.size < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("expected $count fetch(es), saw ${api.calls.size}", api.calls.size >= count)
    }

    @Test
    fun startLoadsTheCacheSynchronously() {
        val cache = FakeCache()
        cache.stored = CachedEnvelope(EnvelopeFixture.envelope(), "\"cached-etag\"")
        // The API never answers; only the cache can be serving.
        val api = ScriptedApi(FetchOutcome.Failure(TransportFailure.Offline))

        startWith(api, cache)

        // No sleep, no await: the cold-start guarantee is that the very next line after
        // start() sees the cached values — a cold start must not answer false briefly.
        assertEquals(true, FortressFlag.isEnabled("dark-mode"))
        assertEquals("buy-now", FortressFlag.stringValue("checkout-cta", default = "x"))
        assertEquals(ValueSource.CACHED, FortressFlag.resolve("dark-mode").source)

        // And the restored ETag rides the first fetch.
        awaitFetch(api)
        assertEquals("\"cached-etag\"", api.calls.first().first)
    }

    @Test
    fun anAcceptedPayloadOverwritesWholesale() {
        val cache = FakeCache()
        cache.stored =
            CachedEnvelope(
                EnvelopeFixture.envelope(EnvelopeFixture.payloadJson(flags = """{"old-flag":true,"dark-mode":false}""")),
                null,
            )
        val api = ScriptedApi(FetchOutcome.Success(EnvelopeFixture.envelope(), "\"e2\""))

        startWith(api, cache)
        awaitFetch(api)
        val deadline = System.currentTimeMillis() + 5000
        while (FortressFlag.resolve("old-flag").source != ValueSource.SAFE_DEFAULT &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(10)
        }

        // The archived flag stopped resolving from EITHER tier on the same poll: archiving
        // in the dashboard is how a customer turns a feature off for good (ADR-0003).
        assertEquals(ValueSource.SAFE_DEFAULT, FortressFlag.resolve("old-flag").source)
        assertEquals(false, FortressFlag.isEnabled("old-flag"))
        assertEquals(ValueSource.FRESH, FortressFlag.resolve("dark-mode").source)
        assertEquals(true, FortressFlag.isEnabled("dark-mode"))
    }

    @Test
    fun aRejectedPayloadNeverTouchesTheCache() {
        val cache = FakeCache()
        val good = EnvelopeFixture.envelope()
        cache.stored = CachedEnvelope(good, null)
        // The server starts serving another environment's payload (or garbage).
        val api =
            ScriptedApi(
                FetchOutcome.Success(EnvelopeFixture.envelope(EnvelopeFixture.payloadJson(environment = "prod")), null),
            )

        startWith(api, cache)
        awaitFetch(api)
        Thread.sleep(50)

        // The device keeps its last known values; the attacker can fail to change them,
        // never erase them.
        assertEquals(0, cache.storeCount)
        assertTrue(cache.stored!!.raw.contentEquals(good))
        assertEquals(true, FortressFlag.isEnabled("dark-mode"))
    }

    @Test
    fun kindProjectionIsFailSafeNeverCoercion() {
        val cache = FakeCache()
        cache.stored = CachedEnvelope(EnvelopeFixture.envelope(), null)
        startWith(ScriptedApi(FetchOutcome.Failure(TransportFailure.Offline)), cache)

        // checkout-cta is a STRING flag: reading it as a boolean answers the caller's
        // default (else false); reading retry-limit (number) as a string answers default.
        assertEquals(true, FortressFlag.isEnabled("checkout-cta", default = true))
        assertEquals(false, FortressFlag.isEnabled("checkout-cta"))
        assertEquals("fallback", FortressFlag.stringValue("retry-limit", default = "fallback"))
        assertEquals(3.0, FortressFlag.numberValue("retry-limit", default = 9.0), 0.0)
    }

    @Test
    fun refreshCoalescesAndNotModifiedIsSuccess() {
        val api = ScriptedApi(FetchOutcome.NotModified)
        startWith(api)
        awaitFetch(api)

        val outcome = runBlocking { FortressFlag.refresh() }
        assertTrue(outcome is RefreshOutcome.Unchanged)
        // A 304 stamped the diagnostics clock: a healthy-but-unchanged device must not
        // look like a failing one.
        assertEquals(EnvelopeFixture.NOW.toEpochMilli(), FortressFlag.diagnostics.lastSuccessfulFetchEpochMillis)
    }

    @Test
    fun aTagChangeClearsTheStoredEtag() {
        val cache = FakeCache()
        cache.stored = CachedEnvelope(EnvelopeFixture.envelope(), "\"e1\"")
        val api = ScriptedApi(FetchOutcome.NotModified)
        startWith(api, cache)
        awaitFetch(api)
        assertEquals("\"e1\"", api.calls.first().first)

        // A different tag set can mean a different payload for the same device — the
        // stored validator no longer names what the next response would be.
        FortressFlag.setTags(mapOf("cohort" to "beta"))
        awaitFetch(api, count = 2)
        val second = api.calls.toList()[1]
        assertNull(second.first)
        assertTrue(second.second != null)
    }

    @Test
    fun allFlagsEnumeratesOnlyWhatTheDeviceHas() {
        val cache = FakeCache()
        cache.stored = CachedEnvelope(EnvelopeFixture.envelope(), null)
        startWith(ScriptedApi(FetchOutcome.Failure(TransportFailure.Offline)), cache)

        val all = FortressFlag.allFlags()
        assertEquals(setOf("dark-mode", "checkout-cta", "retry-limit"), all.keys)
        assertTrue(all.values.all { it.source == ValueSource.CACHED })
    }

    @Test
    fun notStartedIsAnOutcomeNotAnError() {
        FortressFlag.stop()
        assertTrue(runBlocking { FortressFlag.refresh() } is RefreshOutcome.NotStarted)
        // And reads still answer the safe default rather than throwing.
        assertEquals(false, FortressFlag.isEnabled("anything"))
    }

    @Test
    fun anUnverifiableCacheIsDiscardedOnLoad() {
        val cache = FakeCache()
        cache.stored = CachedEnvelope("garbage".toByteArray(), "\"e\"")
        startWith(ScriptedApi(FetchOutcome.Failure(TransportFailure.Offline)), cache)

        assertNull(cache.stored)
        assertEquals(false, FortressFlag.isEnabled("dark-mode"))
    }

    @Test
    fun instantAfterStopValuesKeepResolving() {
        val cache = FakeCache()
        cache.stored = CachedEnvelope(EnvelopeFixture.envelope(), null)
        startWith(ScriptedApi(FetchOutcome.Failure(TransportFailure.Offline)), cache)
        FortressFlag.stop()
        // Values already resolved keep resolving from memory, exactly as documented.
        assertEquals(true, FortressFlag.isEnabled("dark-mode"))
    }
}
