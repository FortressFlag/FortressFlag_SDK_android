package com.fortressflag.sdk.transport

import com.fortressflag.sdk.Environment
import com.fortressflag.sdk.SignaturePolicy
import com.fortressflag.sdk.TrustedKeys
import com.fortressflag.sdk.support.Base64Url
import java.time.Duration
import java.time.Instant

/**
 * Why an envelope was not accepted. A rejection is never fatal: it means "serve the last
 * value this device saw" (Founding §8.4). Enumerated in this much detail because "flags
 * stopped updating" is otherwise one of the hardest things to debug in a customer's app, and
 * the answer should be one log line.
 */
internal sealed class EnvelopeRejection {
    object MalformedEnvelope : EnvelopeRejection()

    object MissingSignature : EnvelopeRejection()

    object MalformedSignature : EnvelopeRejection()

    class UnsupportedSignatureAlgorithm(
        val algorithm: String,
    ) : EnvelopeRejection()

    class UnknownKeyId(
        val keyId: String,
    ) : EnvelopeRejection()

    object BadSignature : EnvelopeRejection()

    object MalformedPayload : EnvelopeRejection()

    class UnsupportedContractVersion(
        val version: Int,
    ) : EnvelopeRejection()

    class EnvironmentMismatch(
        val expected: String,
        val received: String,
    ) : EnvelopeRejection()

    object DeviceMismatch : EnvelopeRejection()

    class Expired(
        val at: Instant,
    ) : EnvelopeRejection()

    class IssuedInTheFuture(
        val at: Instant,
    ) : EnvelopeRejection()

    override fun toString(): String = this::class.simpleName ?: "EnvelopeRejection"
}

/**
 * An envelope that passed every check, kept alongside the exact bytes it arrived as. `raw`
 * is retained so the cache stores what was signed rather than a re-serialisation of what we
 * parsed — re-serialising would silently strip any field a future server adds and break the
 * signature on reload.
 */
internal class VerifiedEnvelope(
    val raw: ByteArray,
    val payload: FlagPayload,
)

internal object EnvelopeVerifier {
    /** What the payload must claim to be, for it to be about us. */
    internal class Expectations(
        val environment: Environment,
        /**
         * The device the payload must be addressed to, or null to skip that check. Null
         * happens in one situation: loading the cache when identity storage is unavailable,
         * so the SDK does not yet know its own identity. Skipping there is deliberate — the
         * alternative is discarding the last recorded value because of a *transient* storage
         * failure, and the file being read is inside the app's own sandbox.
         */
        val deviceId: String?,
        val now: Instant,
        /** Tolerance for a wrong device clock. End users set their clocks; a device an hour
         * fast should not lose flag updates. */
        val clockSkew: Duration = Duration.ofSeconds(300),
        /**
         * Whether `expiresAt` is enforced. **True for a live response, false when loading
         * the cache** — and that asymmetry is the single most load-bearing rule in this SDK.
         *
         * On a live response, expiry is the replay window: without it, anyone who captured a
         * valid response could serve it back forever, pinning a device to old flag values.
         *
         * On a cache load it must NOT apply. Founding §8.4 makes the last recorded value the
         * primary fallback: a device offline for a month still serves what it last saw.
         * Expiring the cache would silently revert every flag on that device to `false` —
         * turning an outage into a feature regression, which is precisely the failure the
         * cascade exists to prevent. **Expiry governs freshness, not validity.**
         */
        val enforceExpiry: Boolean = true,
    )

    internal sealed class Result {
        class Accepted(
            val envelope: VerifiedEnvelope,
        ) : Result()

        class Rejected(
            val rejection: EnvelopeRejection,
        ) : Result()
    }

    fun verify(
        raw: ByteArray,
        policy: SignaturePolicy,
        expectations: Expectations,
    ): Result {
        val envelope =
            EnvelopeParsing.parseEnvelope(raw)
                ?: return Result.Rejected(EnvelopeRejection.MalformedEnvelope)
        val payloadBytes =
            Base64Url.decode(envelope.payload)
                ?: return Result.Rejected(EnvelopeRejection.MalformedEnvelope)

        if (policy is SignaturePolicy.Required) {
            checkSignature(envelope.sig, payloadBytes, policy.trustedKeys)?.let {
                return Result.Rejected(it)
            }
        }

        val payload =
            EnvelopeParsing.parsePayload(payloadBytes)
                ?: return Result.Rejected(EnvelopeRejection.MalformedPayload)

        checkBinding(payload, expectations)?.let { return Result.Rejected(it) }

        return Result.Accepted(VerifiedEnvelope(raw, payload))
    }

    /**
     * The signature *plumbing*, with the crypto primitive deliberately absent (backend
     * ADR-0013): the backend's signing milestone (M4) has not shipped, its algorithm ADR
     * (P-256 vs Ed25519ph) is open, and Android's platform Ed25519 arrives above this SDK's
     * minSdk. So: a missing signature under Required is rejected (fail closed — the iOS
     * behaviour, byte for byte), the `algorithm:keyID:signature` splitting and trust-store
     * lookup are real, and a signature that *survives* those checks is still rejected as
     * [EnvelopeRejection.BadSignature] because no primitive exists to accept it. When M4
     * lands, its ADR decides the primitive and this is where it goes — with a real trust
     * store, this stub can reject valid payloads but can never accept a forged one.
     */
    private fun checkSignature(
        sig: String?,
        payloadBytes: ByteArray,
        trustedKeys: TrustedKeys,
    ): EnvelopeRejection? {
        if (sig == null) return EnvelopeRejection.MissingSignature

        // limit = 3 so a key ID may contain a colon later without a breaking parse change.
        val parts = sig.split(":", limit = 3)
        if (parts.size != 3) return EnvelopeRejection.MalformedSignature

        val algorithm = parts[0]
        val keyId = parts[1]
        if (algorithm != "ed25519") return EnvelopeRejection.UnsupportedSignatureAlgorithm(algorithm)
        if (Base64Url.decode(parts[2]) == null) return EnvelopeRejection.MalformedSignature
        if (!trustedKeys.keysById.containsKey(keyId)) return EnvelopeRejection.UnknownKeyId(keyId)

        // The primitive gap, made explicit. payloadBytes is deliberately unused beyond this
        // point until M4 supplies the algorithm.
        return EnvelopeRejection.BadSignature
    }

    /**
     * Confirms the payload is about *this* device, *this* environment, and *now* — in the
     * contract's order. A signature alone proves only that FortressFlag produced the bytes
     * at some point; without these checks a production payload could be replayed at a dev
     * build, another device's payload served to this one, and yesterday's values pinned in
     * place indefinitely.
     */
    private fun checkBinding(
        payload: FlagPayload,
        expectations: Expectations,
    ): EnvelopeRejection? {
        // The RANGE, not merely the newest: the durable cache may hold a v1 envelope across
        // an SDK upgrade — see SUPPORTED_CONTRACT_VERSIONS.
        if (payload.version !in SUPPORTED_CONTRACT_VERSIONS) {
            return EnvelopeRejection.UnsupportedContractVersion(payload.version)
        }
        if (payload.environment != expectations.environment.key) {
            return EnvelopeRejection.EnvironmentMismatch(expectations.environment.key, payload.environment)
        }
        val expectedDevice = expectations.deviceId
        if (expectedDevice != null && payload.device != expectedDevice) {
            return EnvelopeRejection.DeviceMismatch
        }
        if (Duration.between(expectations.now, payload.issuedAt) > expectations.clockSkew) {
            return EnvelopeRejection.IssuedInTheFuture(payload.issuedAt)
        }
        if (expectations.enforceExpiry &&
            Duration.between(payload.expiresAt, expectations.now) > expectations.clockSkew
        ) {
            return EnvelopeRejection.Expired(payload.expiresAt)
        }
        return null
    }
}
