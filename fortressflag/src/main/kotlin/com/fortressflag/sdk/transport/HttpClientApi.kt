package com.fortressflag.sdk.transport

import com.fortressflag.sdk.BuildInfo
import com.fortressflag.sdk.Configuration
import com.fortressflag.sdk.support.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * The real client transport: one GET, via [HttpURLConnection] — the platform's own client,
 * because a third-party HTTP stack inside someone else's app is supply-chain surface the
 * fetch does not need (ADR-0013; Founding §8.1).
 *
 * Everything here is defensive against a server we do not control — which, on the data
 * plane, includes a CDN edge and whatever a hostile network puts in front of it.
 */
internal class HttpClientApi(
    private val configuration: Configuration,
    private val log: Log,
    /** Injected in tests to intercept the connection; production uses the URL's own. */
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : ClientApi {
    override suspend fun fetch(
        deviceId: String,
        etag: String?,
        tags: String?,
    ): FetchOutcome =
        withContext(Dispatchers.IO) {
            val url = requestUrl() ?: return@withContext FetchOutcome.Failure(TransportFailure.BadRequestUrl)

            // Belt and braces over Configuration.validate(): that runs at start-up and only
            // logs; this is the check that actually stops a plaintext request leaving the
            // device, because a caller who ignored the warning must still not be able to put
            // an SDK key on the wire in the clear.
            if (!transportAcceptable(url)) {
                log.error("refusing to send an SDK key over plaintext HTTP to ${url.host}")
                return@withContext FetchOutcome.Failure(TransportFailure.InsecureTransportRefused)
            }

            var connection: HttpURLConnection? = null
            try {
                connection = open(url)
                connection.requestMethod = "GET"
                connection.connectTimeout = (configuration.requestTimeoutSeconds * 1000).toInt()
                connection.readTimeout = (configuration.requestTimeoutSeconds * 1000).toInt()
                // We keep our own durable cache and re-verify it on load; a second,
                // unverified copy in the platform's HTTP cache would be flag state nobody
                // checks.
                connection.useCaches = false
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", "Bearer ${configuration.sdkKey}")
                connection.setRequestProperty("X-FF-Device", deviceId)
                connection.setRequestProperty("X-FF-SDK", "android/${BuildInfo.VERSION}")
                connection.setRequestProperty("Accept", "application/json")
                if (tags != null) {
                    // Already validated, merged and base64url-encoded (Tags.encode);
                    // header-safe by construction.
                    connection.setRequestProperty("X-FF-Tags", tags)
                }
                if (etag != null) {
                    connection.setRequestProperty("If-None-Match", etag)
                }

                val status = connection.responseCode
                statusOutcome(status, connection)?.let { return@withContext it }

                // Declared length checked before a byte is read, so an obviously oversized
                // response costs nothing. The streaming cap below is what actually enforces
                // the limit, because Content-Length is a claim, not a promise.
                val declared = connection.contentLengthLong
                if (declared > MAX_RESPONSE_BYTES) {
                    return@withContext FetchOutcome.Failure(TransportFailure.ResponseTooLarge)
                }

                val raw =
                    connection.inputStream.use { readCapped(it) }
                        ?: return@withContext FetchOutcome.Failure(TransportFailure.ResponseTooLarge)

                FetchOutcome.Success(raw, connection.getHeaderField("ETag"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                FetchOutcome.Failure(classify(e))
            } finally {
                connection?.disconnect()
            }
        }

    /** Maps a response status to an outcome, or null when the body still has to be read. */
    private fun statusOutcome(
        status: Int,
        connection: HttpURLConnection,
    ): FetchOutcome? =
        when (status) {
            200 -> null
            // A 304 is a successful conversation with the server — FetchOutcome.NotModified
            // documents why that distinction matters.
            304 -> FetchOutcome.NotModified
            401, 403 -> {
                log.error(
                    "The client API rejected this SDK key (HTTP $status). Flags will continue to " +
                        "resolve from the last cached values. Check that the key is for the " +
                        "'${configuration.environment.key}' environment and has not been revoked.",
                )
                FetchOutcome.Failure(TransportFailure.Unauthorized)
            }
            429 -> FetchOutcome.Failure(TransportFailure.RateLimited(retryAfterSeconds(connection)))
            in 500..599 -> FetchOutcome.Failure(TransportFailure.ServerError(status))
            else -> FetchOutcome.Failure(TransportFailure.UnexpectedStatus(status))
        }

    private fun requestUrl(): URL? =
        try {
            val base = configuration.baseUrl.trimEnd('/')
            URL("$base/v1/client/flags?environment=${configuration.environment.key}&v=$SUPPORTED_CONTRACT_VERSION")
        } catch (_: Exception) {
            null
        }

    private fun transportAcceptable(url: URL): Boolean {
        val scheme = url.protocol.lowercase()
        if (scheme == "https") return true
        if (!configuration.allowsInsecureLocalTransport || scheme != "http") return false
        val host = url.host ?: return false
        if (host !in Configuration.LOOPBACK_HOSTS) return false
        log.warning("sending a plaintext request to $host — development only")
        return true
    }

    /** Reads at most [MAX_RESPONSE_BYTES]; null when the stream exceeds it — abandoning the
     * read stops a server streaming forever from costing bandwidth as well as memory. */
    private fun readCapped(stream: InputStream): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream(8 * 1024)
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val n = stream.read(chunk)
            if (n < 0) break
            if (buffer.size() + n > MAX_RESPONSE_BYTES) return null
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }

    private fun classify(e: Exception): TransportFailure =
        when (e) {
            is SocketTimeoutException -> TransportFailure.TimedOut
            is UnknownHostException -> TransportFailure.Offline
            is java.net.ConnectException -> TransportFailure.Offline
            is javax.net.ssl.SSLException -> TransportFailure.Other("TLS: ${e::class.simpleName}")
            is IOException -> TransportFailure.Offline
            else -> TransportFailure.Other(e::class.simpleName ?: "unknown")
        }

    private companion object {
        /**
         * Hard ceiling on a response body: 1 MiB, shared with the cache's bound. A payload
         * is kilobytes; anything approaching this is a server that is broken or hostile, and
         * buffering it would be our memory problem inside someone else's app.
         */
        const val MAX_RESPONSE_BYTES = 1 shl 20

        /**
         * `Retry-After` as seconds. Only the delta-seconds form is honoured; the HTTP-date
         * form is ignored rather than parsed against a device clock we already know may be
         * wrong.
         */
        fun retryAfterSeconds(connection: HttpURLConnection): Double? {
            val raw = connection.getHeaderField("Retry-After")?.trim() ?: return null
            val seconds = raw.toDoubleOrNull() ?: return null
            return if (seconds > 0) seconds else null
        }
    }
}
