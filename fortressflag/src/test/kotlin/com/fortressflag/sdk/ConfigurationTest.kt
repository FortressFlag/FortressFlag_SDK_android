package com.fortressflag.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationTest {
    private fun config(
        sdkKey: String = "ffc_dev_abcdefghijklmnop",
        environment: Environment = Environment.DEVELOPMENT,
        baseUrl: String = Configuration.DEFAULT_BASE_URL,
        allowsInsecure: Boolean = false,
        policy: SignaturePolicy = SignaturePolicy.Disabled,
        refreshSeconds: Long = 300,
    ) = Configuration(
        sdkKey = sdkKey,
        environment = environment,
        baseUrl = baseUrl,
        signaturePolicy = policy,
        refreshIntervalSeconds = refreshSeconds,
        allowsInsecureLocalTransport = allowsInsecure,
    )

    @Test
    fun aSensibleConfigurationHasNoProblems() {
        assertEquals(emptyList<ConfigurationProblem>(), config().validate())
    }

    @Test
    fun keyWithUnderscoresInTheRandomPartIsValid() {
        // The SplitN trap (backend internal/sdkkey): the random part is base64url, whose
        // alphabet includes `_` — roughly three keys in four contain one. An unbounded
        // split would mis-reject them; the limit-3 split must not.
        val problems = config(sdkKey = "ffc_dev_abc_def_ghi").validate()
        assertEquals(emptyList<ConfigurationProblem>(), problems)
    }

    @Test
    fun malformedKeysAreReported() {
        assertTrue(config(sdkKey = "").validate().contains(ConfigurationProblem.EmptySdkKey))
        assertTrue(config(sdkKey = "nonsense").validate().contains(ConfigurationProblem.SdkKeyWrongFormat))
        assertTrue(config(sdkKey = "ffc_dev_").validate().contains(ConfigurationProblem.SdkKeyWrongFormat))
        assertTrue(config(sdkKey = "abc_dev_xyz").validate().contains(ConfigurationProblem.SdkKeyWrongFormat))
    }

    @Test
    fun environmentMismatchIsReported() {
        val problems = config(sdkKey = "ffc_prod_abcdef").validate()
        val mismatch = problems.filterIsInstance<ConfigurationProblem.SdkKeyEnvironmentMismatch>().single()
        assertEquals("prod", mismatch.keyEnvironment)
        assertEquals("dev", mismatch.configured)
    }

    @Test
    fun plainHttpIsReportedUnlessLoopbackAndOptedIn() {
        assertTrue(
            config(baseUrl = "http://example.com")
                .validate()
                .contains(ConfigurationProblem.InsecureBaseUrl),
        )
        assertTrue(
            config(baseUrl = "http://example.com", allowsInsecure = true)
                .validate()
                .filterIsInstance<ConfigurationProblem.InsecureTransportOnNonLoopbackHost>()
                .isNotEmpty(),
        )
        assertEquals(emptyList<ConfigurationProblem>(), config(baseUrl = "http://localhost:8080", allowsInsecure = true).validate())
    }

    @Test
    fun theEmulatorHostAliasCountsAsLoopback() {
        // 10.0.2.2 is the emulator's alias for its host machine — the emulator cannot see
        // `localhost` (that is the emulated device itself). Without this, no local-dev
        // story exists on Android and the failure masquerades as a backend bug.
        assertEquals(
            emptyList<ConfigurationProblem>(),
            config(baseUrl = "http://10.0.2.2:8080", allowsInsecure = true).validate(),
        )
    }

    @Test
    fun requiredPolicyWithEmptyTrustStoreIsReported() {
        val problems = config(policy = SignaturePolicy.Required(TrustedKeys.FORTRESSFLAG_PRODUCTION)).validate()
        assertTrue(problems.contains(ConfigurationProblem.SignatureRequiredButNoTrustedKeys))
    }

    @Test
    fun tooShortRefreshIntervalIsReported() {
        val problems = config(refreshSeconds = 5).validate()
        assertTrue(problems.filterIsInstance<ConfigurationProblem.RefreshIntervalTooShort>().isNotEmpty())
    }

    @Test
    fun environmentKeysAreValidated() {
        assertNotNull(Environment.of("qa"))
        assertNotNull(Environment.of("eu-live"))
        assertNull(Environment.of(""))
        assertNull(Environment.of("a"))
        assertNull(Environment.of("-bad"))
        assertNull(Environment.of("bad-"))
        assertNull(Environment.of("UPPER"))
        assertNull(Environment.of("a".repeat(33)))
    }
}
