package com.fortressflag.sdk.identity

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// THE VECTOR FILE IS A CROSS-PLATFORM CONTRACT, not an implementation detail: iOS asserts the
// same table (IdentityAndCacheTests), the backend's ValidDeviceID enforces the same shape, and
// the canonical copy lives in FortressFlag_Standards/vectors/device-ids.json — the resource
// here is a byte-for-byte copy of it. "One device" has to mean the same thing on every
// platform (Founding §6.1); changing an expectation here is changing the wire contract, and it
// is never a test fix.
class DeviceIdentityFormatTest {
    private fun vectors(): JSONObject {
        val stream =
            checkNotNull(javaClass.classLoader?.getResourceAsStream("device-ids.json")) {
                "device-ids.json missing from test resources"
            }
        return JSONObject(stream.reader().readText())
    }

    @Test
    fun acceptVectors() {
        val accept = vectors().getJSONArray("accept")
        for (i in 0 until accept.length()) {
            val value = accept.getString(i)
            assertTrue("expected accept: $value", DeviceIdentity.isWellFormed(value))
        }
    }

    @Test
    fun rejectVectors() {
        val reject = vectors().getJSONArray("reject")
        for (i in 0 until reject.length()) {
            val value = reject.getString(i)
            assertFalse("expected reject: $value", DeviceIdentity.isWellFormed(value))
        }
    }

    @Test
    fun nonCanonicalFinalCharacterIsRejected() {
        // A 22-char base64url body only strict-decodes when the final character's two low
        // bits are zero; the server decodes with Go's Strict() and answers 400 otherwise.
        // Not in the shared vector file (iOS's Foundation decoder enforces it implicitly),
        // pinned here because java.util.Base64 is lenient and the guard is our own re-encode.
        assertFalse(DeviceIdentity.isWellFormed("dev_AAAAAAAAAAAAAAAAAAAAAB"))
        assertTrue(DeviceIdentity.isWellFormed("dev_AAAAAAAAAAAAAAAAAAAAAQ"))
    }

    @Test
    fun mintedIdentitiesAreWellFormedAndCanonical() {
        repeat(64) {
            val id = DeviceIdentity.mint(simulator = false)
            assertTrue(id, id.startsWith("dev_"))
            assertTrue(id, DeviceIdentity.isWellFormed(id))
        }
        val sim = DeviceIdentity.mint(simulator = true)
        assertTrue(sim, sim.startsWith("sim_"))
        assertTrue(sim, DeviceIdentity.isWellFormed(sim))
    }

    @Test
    fun mintsAreUnique() {
        val minted = (1..128).map { DeviceIdentity.mint(simulator = false) }
        assertEquals(minted.size, minted.toSet().size)
    }

    @Test
    fun emulatorHeuristicRecognisesKnownEmulators() {
        assertTrue(
            DeviceIdentity.isProbablyEmulator(hardware = "ranchu", fingerprint = "x", model = "m", product = "p", manufacturer = "g"),
        )
        assertTrue(
            DeviceIdentity.isProbablyEmulator(hardware = "goldfish", fingerprint = "x", model = "m", product = "p", manufacturer = "g"),
        )
        assertTrue(
            DeviceIdentity.isProbablyEmulator(
                fingerprint = "generic/sdk_gphone",
                model = "m",
                hardware = "h",
                product = "p",
                manufacturer = "g",
            ),
        )
        assertTrue(
            DeviceIdentity.isProbablyEmulator(
                model = "sdk_gphone64_arm64",
                fingerprint = "x",
                hardware = "h",
                product = "p",
                manufacturer = "g",
            ),
        )
    }

    @Test
    fun emulatorHeuristicLeavesRealDevicesAlone() {
        // A wrong `sim_` on a real device is unbilled traffic; the heuristic errs toward
        // `dev_`, so a plausible physical-device tuple must not trip it.
        assertFalse(
            DeviceIdentity.isProbablyEmulator(
                fingerprint = "samsung/x1qxx/x1q:13/TP1A.220624.014/G981XXU6HWJ2:user/release-keys",
                model = "SM-G981B",
                hardware = "exynos990",
                product = "x1qxx",
                manufacturer = "samsung",
            ),
        )
    }
}
