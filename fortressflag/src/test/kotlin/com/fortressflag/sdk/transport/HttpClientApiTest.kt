package com.fortressflag.sdk.transport

import com.fortressflag.sdk.Configuration
import com.fortressflag.sdk.Environment
import com.fortressflag.sdk.LogPolicy
import com.fortressflag.sdk.SignaturePolicy
import com.fortressflag.sdk.support.Log
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Drives the REAL transport class through an injected connection, rather than substituting a
 * fake ClientApi that would only test our idea of what the transport does — the iOS suite's
 * URLProtocol-injection principle, ported.
 */
private class FakeConnection(
    url: URL,
    private val status: Int,
    private val body: ByteArray = ByteArray(0),
    private val headers: Map<String, String> = emptyMap(),
    private val declaredLength: Long? = null,
) : HttpURLConnection(url) {
    val sentHeaders = mutableMapOf<String, String>()

    override fun connect() {}

    override fun disconnect() {}

    override fun usingProxy(): Boolean = false

    override fun setRequestProperty(
        key: String,
        value: String,
    ) {
        sentHeaders[key] = value
        super.setRequestProperty(key, value)
    }

    override fun getResponseCode(): Int = status

    override fun getContentLengthLong(): Long = declaredLength ?: body.size.toLong()

    override fun getHeaderField(name: String): String? = headers[name]

    override fun getInputStream(): InputStream = ByteArrayInputStream(body)
}

class HttpClientApiTest {
    private val log = Log(LogPolicy.SILENT, "test")

    private fun config(
        baseUrl: String = "https://edge.fortressflag.com",
        allowsInsecure: Boolean = false,
    ) = Configuration(
        sdkKey = "ffc_dev_secret",
        environment = Environment.DEVELOPMENT,
        baseUrl = baseUrl,
        signaturePolicy = SignaturePolicy.Disabled,
        allowsInsecureLocalTransport = allowsInsecure,
    )

    private fun fetch(
        connection: (URL) -> FakeConnection,
        configuration: Configuration = config(),
        etag: String? = null,
        tags: String? = null,
    ): Pair<FetchOutcome, FakeConnection?> {
        var made: FakeConnection? = null
        val api =
            HttpClientApi(configuration, log) { url ->
                connection(url).also { made = it }
            }
        val outcome = runBlocking { api.fetch("dev_AAAAAAAAAAAAAAAAAAAAAA", etag, tags) }
        return outcome to made
    }

    @Test
    fun sendsTheContractsHeadersAndQuery() {
        val (outcome, connection) =
            fetch(
                { FakeConnection(it, 200, "{}".toByteArray(), headers = mapOf("ETag" to "\"e1\"")) },
                etag = "\"old\"",
                tags = "encoded-tags",
            )
        val success = outcome as FetchOutcome.Success
        assertEquals("\"e1\"", success.etag)
        val sent = connection ?: error("no connection made")
        assertEquals("Bearer ffc_dev_secret", sent.sentHeaders["Authorization"])
        assertEquals("dev_AAAAAAAAAAAAAAAAAAAAAA", sent.sentHeaders["X-FF-Device"])
        assertTrue(sent.sentHeaders["X-FF-SDK"]!!.startsWith("android/"))
        assertEquals("application/json", sent.sentHeaders["Accept"])
        assertEquals("encoded-tags", sent.sentHeaders["X-FF-Tags"])
        assertEquals("\"old\"", sent.sentHeaders["If-None-Match"])
        assertEquals("/v1/client/flags", sent.url.path)
        assertEquals("environment=dev&v=2", sent.url.query)
    }

    @Test
    fun absentTagsAndEtagSendNoHeaders() {
        val (_, connection) = fetch({ FakeConnection(it, 200, "{}".toByteArray()) })
        val sent = connection ?: error("no connection made")
        assertNull(sent.sentHeaders["X-FF-Tags"])
        assertNull(sent.sentHeaders["If-None-Match"])
    }

    @Test
    fun statusMapping() {
        assertTrue(fetch({ FakeConnection(it, 304) }).first is FetchOutcome.NotModified)
        val unauthorized = fetch({ FakeConnection(it, 401) }).first as FetchOutcome.Failure
        assertTrue(unauthorized.failure is TransportFailure.Unauthorized)
        val forbidden = fetch({ FakeConnection(it, 403) }).first as FetchOutcome.Failure
        assertTrue(forbidden.failure is TransportFailure.Unauthorized)
        val server = fetch({ FakeConnection(it, 503) }).first as FetchOutcome.Failure
        assertEquals(503, (server.failure as TransportFailure.ServerError).status)
        val odd = fetch({ FakeConnection(it, 418) }).first as FetchOutcome.Failure
        assertTrue(odd.failure is TransportFailure.UnexpectedStatus)
    }

    @Test
    fun retryAfterDeltaSecondsIsParsedAndDatesAreIgnored() {
        val limited =
            fetch(
                { FakeConnection(it, 429, headers = mapOf("Retry-After" to "120")) },
            ).first as FetchOutcome.Failure
        assertEquals(120.0, (limited.failure as TransportFailure.RateLimited).retryAfterSeconds!!, 0.0)

        // The HTTP-date form is ignored rather than parsed against a device clock we
        // already know may be wrong.
        val dated =
            fetch(
                { FakeConnection(it, 429, headers = mapOf("Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT")) },
            ).first as FetchOutcome.Failure
        assertNull((dated.failure as TransportFailure.RateLimited).retryAfterSeconds)
    }

    @Test
    fun oversizeIsRefusedOnDeclaredLengthAndOnStreamedBytes() {
        // Declared: costs nothing, no byte read.
        val declared =
            fetch(
                { FakeConnection(it, 200, ByteArray(16), declaredLength = 10L shl 20) },
            ).first as FetchOutcome.Failure
        assertTrue(declared.failure is TransportFailure.ResponseTooLarge)

        // Streamed: Content-Length is a claim, not a promise.
        val streamed =
            fetch(
                { FakeConnection(it, 200, ByteArray((1 shl 20) + 1), declaredLength = 64L) },
            ).first as FetchOutcome.Failure
        assertTrue(streamed.failure is TransportFailure.ResponseTooLarge)
    }

    @Test
    fun plaintextIsRefusedExceptLoopbackUnderOptIn() {
        // A caller who ignored the validate() warning must still not be able to put an SDK
        // key on the wire in the clear.
        val refused =
            fetch(
                { FakeConnection(it, 200, "{}".toByteArray()) },
                configuration = config(baseUrl = "http://evil.example.com"),
            ).first as FetchOutcome.Failure
        assertTrue(refused.failure is TransportFailure.InsecureTransportRefused)

        // 10.0.2.2 — the emulator's host alias — must be allowed, or no local-dev story
        // exists on Android.
        val emulatorHost =
            fetch(
                { FakeConnection(it, 200, "{}".toByteArray()) },
                configuration = config(baseUrl = "http://10.0.2.2:8080", allowsInsecure = true),
            ).first
        assertTrue(emulatorHost is FetchOutcome.Success)
    }
}
