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

/** Builds envelopes the way the server does, for tests. */
internal object EnvelopeFixture {
    val NOW: Instant = Instant.parse("2026-08-18T10:00:00Z")
    const val DEVICE = "dev_AAAAAAAAAAAAAAAAAAAAAA"

    fun payloadJson(
        version: Int = 2,
        environment: String = "dev",
        device: String = DEVICE,
        issuedAt: String = "2026-08-18T10:00:00Z",
        expiresAt: String = "2026-08-18T10:30:00Z",
        flags: String = """{"dark-mode":true,"checkout-cta":"buy-now","retry-limit":3}""",
        extraTopLevel: String? = null,
    ): String {
        val extra = if (extraTopLevel != null) ",$extraTopLevel" else ""
        return """{"v":$version,"tenant":"t","environment":"$environment","device":"$device",""" +
            """"issuedAt":"$issuedAt","expiresAt":"$expiresAt","flags":$flags$extra}"""
    }

    fun envelope(
        payloadJson: String = payloadJson(),
        sig: String? = null,
    ): ByteArray {
        val json = JSONObject()
        json.put("payload", Base64Url.encode(payloadJson.toByteArray(Charsets.UTF_8)))
        if (sig != null) json.put("sig", sig)
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun expectations(
        deviceId: String? = DEVICE,
        now: Instant = NOW,
        enforceExpiry: Boolean = true,
    ) = EnvelopeVerifier.Expectations(
        environment = Environment.DEVELOPMENT,
        deviceId = deviceId,
        now = now,
        enforceExpiry = enforceExpiry,
    )
}

class EnvelopeVerifierTest {
    private fun verify(
        raw: ByteArray,
        policy: SignaturePolicy = SignaturePolicy.Disabled,
        expectations: EnvelopeVerifier.Expectations = EnvelopeFixture.expectations(),
    ) = EnvelopeVerifier.verify(raw, policy, expectations)

    private fun rejectionOf(result: EnvelopeVerifier.Result): EnvelopeRejection = (result as EnvelopeVerifier.Result.Rejected).rejection

    @Test
    fun aWellFormedUnsignedEnvelopeIsAcceptedUnderDisabledPolicy() {
        val result = verify(EnvelopeFixture.envelope())
        val accepted = result as EnvelopeVerifier.Result.Accepted
        assertEquals(3, accepted.envelope.payload.flags.size)
        assertEquals(
            true,
            accepted.envelope.payload.flags["dark-mode"]
                ?.boolValue,
        )
        assertEquals(
            "buy-now",
            accepted.envelope.payload.flags["checkout-cta"]
                ?.stringValue,
        )
        assertEquals(
            3.0,
            accepted.envelope.payload.flags["retry-limit"]
                ?.numberValue,
        )
    }

    @Test
    fun unsignedUnderRequiredIsRejected() {
        // The `sig` field is absent in every real response until backend M4 ships. Required
        // + unsigned → rejected-to-cache is iOS behaviour, byte for byte: fail closed.
        val result =
            verify(
                EnvelopeFixture.envelope(),
                policy = SignaturePolicy.Required(TrustedKeys.FORTRESSFLAG_PRODUCTION),
            )
        assertEquals(EnvelopeRejection.MissingSignature, rejectionOf(result))
    }

    @Test
    fun signedButUntrustedKeyIsUnknownKeyId() {
        val result =
            verify(
                EnvelopeFixture.envelope(sig = "ed25519:2026-key1:${"sig"}"),
                policy = SignaturePolicy.Required(TrustedKeys(mapOf("other" to ByteArray(32)))),
            )
        assertTrue(rejectionOf(result) is EnvelopeRejection.UnknownKeyId)
    }

    @Test
    fun aTrustedKeyStillRejectsUntilM4SuppliesThePrimitive() {
        // The plumbing without the crypto (ADR-0013): with a trust store entry present the
        // stub must REJECT, never accept — a stub that can reject valid payloads is safe, a
        // stub that could accept forged ones is not.
        val result =
            verify(
                EnvelopeFixture.envelope(sig = "ed25519:k1:AAAA"),
                policy = SignaturePolicy.Required(TrustedKeys(mapOf("k1" to ByteArray(32)))),
            )
        assertEquals(EnvelopeRejection.BadSignature, rejectionOf(result))
    }

    @Test
    fun unknownAlgorithmIsNamed() {
        val result =
            verify(
                EnvelopeFixture.envelope(sig = "p256:k1:AAAA"),
                policy = SignaturePolicy.Required(TrustedKeys(mapOf("k1" to ByteArray(32)))),
            )
        assertTrue(rejectionOf(result) is EnvelopeRejection.UnsupportedSignatureAlgorithm)
    }

    @Test
    fun bindingChecksFireInTheContractsOrder() {
        // version range
        assertTrue(
            rejectionOf(verify(EnvelopeFixture.envelope(EnvelopeFixture.payloadJson(version = 99))))
                is EnvelopeRejection.UnsupportedContractVersion,
        )
        // environment
        assertTrue(
            rejectionOf(verify(EnvelopeFixture.envelope(EnvelopeFixture.payloadJson(environment = "prod"))))
                is EnvelopeRejection.EnvironmentMismatch,
        )
        // device
        assertEquals(
            EnvelopeRejection.DeviceMismatch,
            rejectionOf(verify(EnvelopeFixture.envelope(EnvelopeFixture.payloadJson(device = "dev_BBBBBBBBBBBBBBBBBBAAAA")))),
        )
        // issuedAt in the future beyond skew
        assertTrue(
            rejectionOf(verify(EnvelopeFixture.envelope(EnvelopeFixture.payloadJson(issuedAt = "2026-08-18T11:00:00Z"))))
                is EnvelopeRejection.IssuedInTheFuture,
        )
        // expiresAt passed beyond skew
        assertTrue(
            rejectionOf(
                verify(
                    EnvelopeFixture.envelope(
                        EnvelopeFixture.payloadJson(issuedAt = "2026-08-18T08:00:00Z", expiresAt = "2026-08-18T08:30:00Z"),
                    ),
                ),
            ) is EnvelopeRejection.Expired,
        )
    }

    @Test
    fun aV1PayloadDecodesIntoTheUnion() {
        val result =
            verify(
                EnvelopeFixture.envelope(EnvelopeFixture.payloadJson(version = 1, flags = """{"dark-mode":true}""")),
            )
        val accepted = result as EnvelopeVerifier.Result.Accepted
        assertEquals(1, accepted.envelope.payload.version)
        assertEquals(
            true,
            accepted.envelope.payload.flags["dark-mode"]
                ?.boolValue,
        )
    }

    @Test
    fun theExpiryAsymmetry() {
        // THE single most load-bearing rule: expiry governs freshness, not validity. The
        // same expired envelope is rejected live and accepted on a cache load — a device
        // offline for a month still serves what it last saw (Founding §8.4).
        val expired =
            EnvelopeFixture.envelope(
                EnvelopeFixture.payloadJson(issuedAt = "2026-07-01T10:00:00Z", expiresAt = "2026-07-01T10:30:00Z"),
            )
        assertTrue(verify(expired) is EnvelopeVerifier.Result.Rejected)
        assertTrue(
            verify(expired, expectations = EnvelopeFixture.expectations(enforceExpiry = false))
                is EnvelopeVerifier.Result.Accepted,
        )
    }

    @Test
    fun aWrongClockWithinSkewIsTolerated() {
        // End users set their clocks; a device five minutes fast must not lose updates.
        val result =
            verify(
                EnvelopeFixture.envelope(EnvelopeFixture.payloadJson(issuedAt = "2026-08-18T10:04:00Z")),
            )
        assertTrue(result is EnvelopeVerifier.Result.Accepted)
    }

    @Test
    fun nullDeviceExpectationSkipsTheDeviceCheck() {
        // The cache-load-without-identity case: refusing the check would discard the last
        // recorded value over a transient storage failure.
        val result =
            verify(
                EnvelopeFixture.envelope(),
                expectations = EnvelopeFixture.expectations(deviceId = null),
            )
        assertTrue(result is EnvelopeVerifier.Result.Accepted)
    }

    @Test
    fun malformedShapesRejectTheWholeEnvelope() {
        assertEquals(EnvelopeRejection.MalformedEnvelope, rejectionOf(verify("not json".toByteArray())))
        assertEquals(EnvelopeRejection.MalformedEnvelope, rejectionOf(verify("{}".toByteArray())))
        assertEquals(
            EnvelopeRejection.MalformedEnvelope,
            rejectionOf(verify("""{"payload":"!!!not-base64url!!!"}""".toByteArray())),
        )
        // The value union is scalars only: null, object and array values reject the WHOLE
        // envelope — never a guess at a shape this build does not understand.
        for (bad in listOf("null", "{}", "[1,2]")) {
            assertEquals(
                "flag value $bad",
                EnvelopeRejection.MalformedPayload,
                rejectionOf(verify(EnvelopeFixture.envelope(EnvelopeFixture.payloadJson(flags = """{"x":$bad}""")))),
            )
        }
    }

    @Test
    fun unknownTopLevelPayloadFieldsAreIgnored() {
        // The server may add fields additively (contract versioning rules).
        val result =
            verify(
                EnvelopeFixture.envelope(EnvelopeFixture.payloadJson(extraTopLevel = """"future":"field"""")),
            )
        assertTrue(result is EnvelopeVerifier.Result.Accepted)
    }

    @Test
    fun rfc3339FractionalSecondsBothParse() {
        // A Go-side RFC3339 → RFC3339Nano change is invisible in review and must not brick
        // shipped SDKs; this tolerance is deliberate, not laxity.
        val withFraction =
            verify(
                EnvelopeFixture.envelope(
                    EnvelopeFixture.payloadJson(issuedAt = "2026-08-18T10:00:00.123456Z", expiresAt = "2026-08-18T10:30:00.5Z"),
                ),
            )
        assertTrue(withFraction is EnvelopeVerifier.Result.Accepted)
        val offsetForm =
            verify(
                EnvelopeFixture.envelope(
                    EnvelopeFixture.payloadJson(issuedAt = "2026-08-18T10:00:00+00:00"),
                ),
            )
        assertTrue(offsetForm is EnvelopeVerifier.Result.Accepted)
    }
}
