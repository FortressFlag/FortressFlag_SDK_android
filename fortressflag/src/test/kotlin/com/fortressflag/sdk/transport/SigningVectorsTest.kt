package com.fortressflag.sdk.transport

import com.fortressflag.sdk.Environment
import com.fortressflag.sdk.SignaturePolicy
import com.fortressflag.sdk.TrustedKeys
import com.fortressflag.sdk.support.Base64Url
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The cross-SDK signature vectors (backend ADR-0025); the canonical copy lives in
 * FortressFlag_Standards/vectors/signing.json — the resource is a byte-for-byte copy. Every
 * `envelope` is the exact wire bytes and is fed to the verifier unchanged: nobody
 * re-serialises, which is the whole point of a detached signature.
 */
class SigningVectorsTest {
    private val vectors: JSONObject by lazy {
        val stream =
            checkNotNull(javaClass.classLoader?.getResourceAsStream("signing.json")) {
                "signing.json missing from test resources"
            }
        JSONObject(stream.reader().readText())
    }

    private val keyId: String get() = vectors.getString("keyId")
    private val publicKey: ByteArray get() = checkNotNull(Base64Url.decode(vectors.getString("publicKey")))

    private fun policy(key: ByteArray = publicKey) = SignaturePolicy.Required(TrustedKeys(mapOf(keyId to key)))

    // The vector payloads bind environment `prod`, device dev_AAAAAAAAAAAAAAAAAAAAAA, issued
    // 2026-09-16, expiring 2099 — so any `now` in between keeps the binding checks green.
    private val expectations =
        EnvelopeVerifier.Expectations(
            environment = Environment.PRODUCTION,
            deviceId = "dev_AAAAAAAAAAAAAAAAAAAAAA",
            now = Instant.parse("2026-09-16T12:00:00Z"),
        )

    private fun envelopeBytes(entry: JSONObject): ByteArray = checkNotNull(Base64Url.decode(entry.getString("envelope")))

    private fun entries(list: String): List<JSONObject> {
        val arr = vectors.getJSONArray(list)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    @Test
    fun thePublicKeyIsThirtyTwoRawBytes() {
        assertEquals(32, publicKey.size)
        assertEquals("vector-k1", keyId)
    }

    @Test
    fun acceptEntriesVerify() {
        val accept = entries("accept")
        assertEquals(2, accept.size)
        for (entry in accept) {
            val name = entry.getString("name")
            val raw = envelopeBytes(entry)
            if (name == "client-v2") {
                val result = EnvelopeVerifier.verify(raw, policy(), expectations)
                assertTrue(name, result is EnvelopeVerifier.Result.Accepted)
                val payload = (result as EnvelopeVerifier.Result.Accepted).envelope.payload
                assertEquals(true, payload.flags["dark-mode"]?.boolValue)
                assertEquals("buy-now", payload.flags["checkout-cta"]?.stringValue)
                assertEquals(3.0, payload.flags["max-items"]?.numberValue)
            } else {
                // server-sv1 is a ruleset document a CLIENT SDK never parses; what it pins
                // here is the primitive over server-shaped bytes, via the same signature path.
                val env = JSONObject(String(raw, Charsets.UTF_8))
                val payloadBytes = checkNotNull(Base64Url.decode(env.getString("payload")))
                val sig = env.getString("sig").split(":", limit = 3)
                assertEquals(name, "ed25519", sig[0])
                assertEquals(name, keyId, sig[1])
                assertTrue(name, Ed25519.verify(publicKey, payloadBytes, checkNotNull(Base64Url.decode(sig[2]))))
            }
        }
    }

    @Test
    fun rejectEntriesFailWithTheNamedCode() {
        val reject = entries("reject")
        assertEquals(4, reject.size)
        for (entry in reject) {
            val result = EnvelopeVerifier.verify(envelopeBytes(entry), policy(), expectations)
            val rejection = (result as EnvelopeVerifier.Result.Rejected).rejection
            val got =
                when (rejection) {
                    is EnvelopeRejection.BadSignature -> "badSignature"
                    is EnvelopeRejection.UnknownKeyId -> "unknownKeyId"
                    is EnvelopeRejection.UnsupportedSignatureAlgorithm -> "unsupportedSignatureAlgorithm"
                    is EnvelopeRejection.MissingSignature -> "missingSignature"
                    is EnvelopeRejection.MalformedSignature -> "malformedSignature"
                    else -> rejection.toString()
                }
            assertEquals(entry.getString("name"), entry.getString("code"), got)
        }
    }

    @Test
    fun theWrongKeyForAKnownKeyIdIsBadSignature() {
        val other = ByteArray(32).also { it[0] = 0x01 } // y = 1: a valid point, not ours
        val entry = entries("accept").first { it.getString("name") == "client-v2" }
        val result = EnvelopeVerifier.verify(envelopeBytes(entry), policy(other), expectations)
        assertEquals(EnvelopeRejection.BadSignature, (result as EnvelopeVerifier.Result.Rejected).rejection)
    }

    @Test
    fun aMalformedKeyInTheTrustStoreIsUnknownKeyId() {
        val entry = entries("accept").first { it.getString("name") == "client-v2" }
        val result = EnvelopeVerifier.verify(envelopeBytes(entry), policy(ByteArray(31)), expectations)
        assertTrue((result as EnvelopeVerifier.Result.Rejected).rejection is EnvelopeRejection.UnknownKeyId)
    }

    @Test
    fun disabledAcceptsTheUnsignedEntry() {
        val entry = entries("reject").first { it.getString("name") == "unsigned" }
        val result = EnvelopeVerifier.verify(envelopeBytes(entry), SignaturePolicy.Disabled, expectations)
        assertTrue(result is EnvelopeVerifier.Result.Accepted)
    }
}
