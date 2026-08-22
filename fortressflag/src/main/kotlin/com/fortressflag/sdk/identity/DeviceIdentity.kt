package com.fortressflag.sdk.identity

import android.os.Build
import java.security.SecureRandom
import java.util.Base64

/**
 * The device-identity format: the cross-platform contract in
 * `FortressFlag_Standards/contracts/device-identity.md` (Founding §6.1 — the billing
 * primitive, and a GDPR pseudonymous identifier).
 *
 * `dev_` + unpadded base64url of 16 bytes from [SecureRandom] — or `sim_` with the identical
 * body when the emulator heuristic fires. Random, never derived: not ANDROID_ID, not IMEI,
 * not a serial — and not a hash of any of those, whose input spaces are small enough to
 * enumerate, making the "one-way" function reversible in practice.
 */
public object DeviceIdentity {
    internal const val DEV_PREFIX: String = "dev_"
    internal const val SIM_PREFIX: String = "sim_"
    private const val BODY_LENGTH = 22 // unpadded base64url of 16 bytes
    private const val ENTROPY_BYTES = 16

    private val random = SecureRandom()

    /**
     * Whether [value] is shaped like an identity we minted. BOTH prefixes are accepted
     * regardless of which one this build mints: a stored `dev_` identity later read in an
     * emulator is kept, because identity stability wins over prefix purity. Anything else is
     * treated as absent rather than trusted — adopting a corrupt or foreign value puts junk
     * in the billing path.
     */
    public fun isWellFormed(value: String): Boolean {
        val body =
            when {
                value.startsWith(DEV_PREFIX) -> value.substring(DEV_PREFIX.length)
                value.startsWith(SIM_PREFIX) -> value.substring(SIM_PREFIX.length)
                else -> return false
            }
        if (body.length != BODY_LENGTH) return false
        val decoded =
            try {
                Base64.getUrlDecoder().decode(body)
            } catch (_: IllegalArgumentException) {
                return false
            }
        if (decoded.size != ENTROPY_BYTES) return false
        // Strict canonical check: the server decodes with Go's Strict(), which rejects a
        // final character whose low bits are non-zero (a 22-char body only round-trips when
        // the 22nd char is A/Q/g/w …). java.util.Base64 is lenient there, so re-encode and
        // compare — a value that passes here is a value the server will not 400.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == body
    }

    /** Mints a fresh identity with the prefix this build's environment calls for. */
    internal fun mint(simulator: Boolean = isProbablyEmulator()): String {
        val bytes = ByteArray(ENTROPY_BYTES)
        random.nextBytes(bytes)
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return (if (simulator) SIM_PREFIX else DEV_PREFIX) + body
    }

    /**
     * Best-effort emulator detection (the contract's own words: compile-time-exact on iOS,
     * best-effort on Android). The server serves `sim_` identities flags normally, excludes
     * them from seat metering, and tallies sim traffic to spot builds that lie — so a wrong
     * answer here is a billing skew, not an outage, and the heuristic errs toward `dev_`.
     */
    internal fun isProbablyEmulator(
        fingerprint: String = Build.FINGERPRINT ?: "",
        model: String = Build.MODEL ?: "",
        hardware: String = Build.HARDWARE ?: "",
        product: String = Build.PRODUCT ?: "",
        manufacturer: String = Build.MANUFACTURER ?: "",
    ): Boolean =
        fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            fingerprint.contains("emulator") ||
            model.contains("Emulator") ||
            model.contains("Android SDK built for") ||
            model.startsWith("sdk_gphone") ||
            hardware == "goldfish" ||
            hardware == "ranchu" ||
            hardware.contains("cutf") ||
            product.startsWith("sdk") ||
            product.startsWith("google_sdk") ||
            product.contains("emulator") ||
            (manufacturer == "Google" && product.startsWith("sdk_gphone"))
}
