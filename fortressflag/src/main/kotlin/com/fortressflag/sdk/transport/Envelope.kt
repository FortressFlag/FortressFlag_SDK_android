package com.fortressflag.sdk.transport

import com.fortressflag.sdk.FlagValue
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField

/**
 * The contract version this SDK build ASKS FOR (`?v=2` on every fetch). Checked, not
 * assumed: an SDK that meets a `v` it does not know must fall back to the cache rather than
 * guess at a payload whose meaning has changed (Founding §5).
 */
internal const val SUPPORTED_CONTRACT_VERSION = 2

/**
 * Every version this build can DECODE, as a range. v1 stays accepted for one load-bearing
 * reason: the durable cache holds whatever envelope the device last accepted, and the first
 * launch after an SDK upgrade reads a v1 file. Rejecting it would boot every updating
 * customer's app into the `false` fallback for a session — the exact flicker the cache
 * exists to prevent (Founding §8.4).
 */
internal val SUPPORTED_CONTRACT_VERSIONS: IntRange = 1..SUPPORTED_CONTRACT_VERSION

/**
 * The outer envelope: an opaque payload and a detached signature over its exact bytes.
 *
 * **Nothing lives outside the signature.** An inline JSON object with a `sig` field
 * alongside the data would require the server and every SDK to agree, byte for byte, on a
 * canonical serialisation; any divergence between Go and Kotlin would fail on some payloads
 * and not others, on devices we cannot debug and cannot recall. Signing the literal
 * transmitted bytes removes that class of bug — and means no field can be trusted before
 * the signature check, because there is no field to read.
 */
internal class SignedEnvelope(
    /** Unpadded base64url of the payload JSON. */
    val payload: String,
    /**
     * `ed25519:<keyID>:<unpadded base64url signature>`. Absent only when the server is not
     * signing (true of every real response until backend M4), which
     * `SignaturePolicy.Required` rejects.
     */
    val sig: String?,
)

/**
 * The signed payload: this device's flag values for one environment at one moment.
 *
 * Note what is *not* here. No flag names, no descriptions, no `updatedAt` — those are
 * management prose that must never reach an end-user's device (Founding §2.1). The device
 * gets keys and scalar values.
 */
internal class FlagPayload(
    val version: Int,
    val tenant: String,
    val environment: String,
    val device: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val flags: Map<String, FlagValue>,
)

internal object EnvelopeParsing {
    /** Decodes the outer envelope, or null when it is not the envelope shape. */
    fun parseEnvelope(raw: ByteArray): SignedEnvelope? {
        val json =
            try {
                JSONObject(String(raw, Charsets.UTF_8))
            } catch (_: Exception) {
                return null
            }
        val payload = json.optString("payload", "")
        if (payload.isEmpty()) return null
        val sig = if (json.has("sig") && !json.isNull("sig")) json.optString("sig", "") else null
        return SignedEnvelope(payload, sig)
    }

    /**
     * Decodes the inner payload, or null on any malformation — including a flag value that
     * is null, an object or an array: the union is scalars only (ADR-0008), and a shape this
     * build does not understand rejects the WHOLE envelope. Unknown top-level fields are
     * ignored so the server can add fields additively.
     */
    fun parsePayload(payloadBytes: ByteArray): FlagPayload? {
        val json =
            try {
                JSONObject(String(payloadBytes, Charsets.UTF_8))
            } catch (_: Exception) {
                return null
            }
        return try {
            val flagsJson = json.getJSONObject("flags")
            val flags = LinkedHashMap<String, FlagValue>(flagsJson.length())
            for (key in flagsJson.keys()) {
                flags[key] = decodeValue(flagsJson.get(key)) ?: return null
            }
            FlagPayload(
                version = json.getInt("v"),
                tenant = json.getString("tenant"),
                environment = json.getString("environment"),
                device = json.getString("device"),
                issuedAt = Rfc3339.parse(json.getString("issuedAt")) ?: return null,
                expiresAt = Rfc3339.parse(json.getString("expiresAt")) ?: return null,
                flags = flags,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** The value union: a bare JSON boolean, string or number. Anything else is null. */
    private fun decodeValue(raw: Any?): FlagValue? =
        when (raw) {
            is Boolean -> FlagValue.Bool(raw)
            is String -> FlagValue.Str(raw)
            // org.json materialises JSON numbers as Integer, Long, Double or BigDecimal
            // depending on their spelling; the contract says float64, so all collapse to it.
            is Number -> FlagValue.Num(raw.toDouble())
            else -> null
        }
}

/**
 * RFC 3339, accepted **with or without fractional seconds** (and with `+00:00`-style offsets
 * as well as `Z`).
 *
 * The backend emits `time.RFC3339` (no fraction) today. Accepting both is not laxity: a
 * server-side change from `RFC3339` to `RFC3339Nano` is invisible in a Go code review and
 * would otherwise brick every shipped SDK (Founding §5). `DateTimeFormatter.ISO_OFFSET_DATE_TIME`
 * treats the fraction as optional, which is exactly the tolerance the iOS SDK made
 * deliberate — a single-pattern `SimpleDateFormat` would silently fail one of the two forms.
 */
internal object Rfc3339 {
    fun parse(raw: String): Instant? =
        try {
            val parsed = DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(raw)
            Instant.ofEpochSecond(
                parsed.getLong(ChronoField.INSTANT_SECONDS),
                parsed.getLong(ChronoField.NANO_OF_SECOND),
            )
        } catch (_: DateTimeParseException) {
            null
        } catch (_: Exception) {
            null
        }
}
