package com.fortressflag.sdk.transport

/** Why a fetch did not produce an envelope. */
internal sealed class TransportFailure {
    object Offline : TransportFailure()

    object TimedOut : TransportFailure()

    /** The SDK key was rejected or revoked. Not fatal — the cache keeps answering. */
    object Unauthorized : TransportFailure()

    class RateLimited(
        val retryAfterSeconds: Double?,
    ) : TransportFailure()

    class ServerError(
        val status: Int,
    ) : TransportFailure()

    class UnexpectedStatus(
        val status: Int,
    ) : TransportFailure()

    object ResponseTooLarge : TransportFailure()

    object Cancelled : TransportFailure()

    object InsecureTransportRefused : TransportFailure()

    object BadRequestUrl : TransportFailure()

    class Other(
        val description: String,
    ) : TransportFailure()
}

/** The result of one attempt to fetch this device's flag values. */
internal sealed class FetchOutcome {
    /**
     * The exact response bytes, unparsed. Verification happens above this layer so that the
     * transport never has an opinion about whether a payload is trustworthy.
     */
    class Success(
        val raw: ByteArray,
        val etag: String?,
    ) : FetchOutcome()

    /**
     * The server confirmed our cached copy is current — **and it is a success**: it stamps
     * `lastSuccessfulFetch`, because a device whose flags simply have not changed for a week
     * must not look, on a diagnostics screen, like one that has been failing for a week.
     */
    object NotModified : FetchOutcome()

    class Failure(
        val failure: TransportFailure,
    ) : FetchOutcome()
}

/**
 * The client data-plane contract, as the SDK consumes it. An interface so the same code path
 * runs against the control plane today, the CDN edge tomorrow, and a hostile test double in
 * the chaos tests.
 *
 * `tags` is the pre-encoded `X-FF-Tags` header value, or null to send none — encoded above
 * this layer (see Tags.encode) so the transport stays a dumb pipe.
 */
internal interface ClientApi {
    suspend fun fetch(
        deviceId: String,
        etag: String?,
        tags: String?,
    ): FetchOutcome
}
