package com.fortressflag.sdk

/**
 * Which of the customer's environments this app build reads flags from.
 *
 * A validated value type, not a free string. The set of environments is the customer's own
 * data (they create `qa` or `eu-live` in the dashboard), but "which environment am I?" must
 * never be arbitrary text: on a product whose entire job is answering "is this on in
 * production?", a typo that silently reads the wrong environment is the worst failure mode
 * available. [of] enforces the same key format the server's `environments_key_format` CHECK
 * does (2–32 chars, lowercase letters, digits and hyphens, starting and ending with a letter
 * or digit) and returns null rather than carrying rubbish.
 */
public class Environment private constructor(
    public val key: String,
) {
    override fun equals(other: Any?): Boolean = other is Environment && other.key == key

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = key

    public companion object {
        /** The seeded default every tenant starts with, key `dev`. */
        public val DEVELOPMENT: Environment = Environment("dev")

        /** The seeded default with key `staging`. */
        public val STAGING: Environment = Environment("staging")

        /**
         * The seeded default with key `prod`. Whether an environment is treated as production
         * by the control plane is a property of the tenant's row (`is_production`), not of
         * this name — the SDK reads whatever environment its key is scoped to.
         */
        public val PRODUCTION: Environment = Environment("prod")

        /**
         * An environment the customer defined in the dashboard, or null when [key] is not
         * shaped like an environment key — null rather than acceptance, because a malformed
         * key could never name an environment on any tenant, and carrying it forward turns a
         * compile-adjacent mistake into a runtime "flags silently never load".
         */
        public fun of(key: String): Environment? = if (isValidKey(key)) Environment(key) else null

        private fun isValidKey(key: String): Boolean {
            if (key.length < 2 || key.length > 32) return false

            fun lowerAlphanumeric(c: Char) = c in 'a'..'z' || c in '0'..'9'
            if (!lowerAlphanumeric(key.first()) || !lowerAlphanumeric(key.last())) return false
            return key.all { lowerAlphanumeric(it) || it == '-' }
        }
    }
}

/**
 * How the SDK treats the signature on a flag payload.
 *
 * The default is [Required]. Rejecting an unverifiable payload is safe here in a way it is
 * not in most systems: rejection means "serve the last value this device saw", not "break
 * the app" (Founding §8.4) — so there is no availability argument for verifying loosely.
 */
public sealed class SignaturePolicy {
    /** Reject any payload not signed by one of [trustedKeys]. */
    public class Required(
        public val trustedKeys: TrustedKeys,
    ) : SignaturePolicy()

    /**
     * Accept unsigned payloads. Intended for local development against a backend that does
     * not hold signing keys yet. A named, greppable choice rather than a silent fallback so
     * that "why is this not verifying?" always has an answer in the customer's own source.
     */
    public object Disabled : SignaturePolicy()
}

/**
 * Public keys the SDK will accept payload signatures from, keyed by the key ID that appears
 * in an envelope's `sig` field. Keyed rather than a bare list so that rotation is a publish,
 * not an app release.
 */
public class TrustedKeys(
    keysById: Map<String, ByteArray>,
) {
    public val keysById: Map<String, ByteArray> = keysById.toMap()

    public val isEmpty: Boolean get() = keysById.isEmpty()

    public companion object {
        /**
         * The keys FortressFlag signs production payloads with.
         *
         * Empty until the backend's signing service (roadmap M4) exists — its algorithm ADR
         * is also what decides the verification primitive here, which is why this SDK ships
         * the policy and the envelope plumbing but no crypto (backend ADR-0013). Empty means
         * [SignaturePolicy.Required] rejects everything, which is the correct fail-closed
         * behaviour for an unverifiable payload — during local development use
         * [SignaturePolicy.Disabled] explicitly.
         */
        public val FORTRESSFLAG_PRODUCTION: TrustedKeys = TrustedKeys(emptyMap())
    }
}

/** How much the SDK writes to logcat. */
public enum class LogPolicy {
    /** Errors and one-time configuration warnings only. The default. */
    STANDARD,

    /**
     * Adds refresh lifecycle and resolution sources. Never enable in a shipping build: it
     * names the customer's flag keys in the device log.
     */
    VERBOSE,

    /** Nothing at all. */
    SILENT,
}

/** A problem with a [Configuration], reported rather than thrown. */
public sealed class ConfigurationProblem {
    public object EmptySdkKey : ConfigurationProblem() {
        override fun toString(): String = "sdkKey is empty."
    }

    public object SdkKeyWrongFormat : ConfigurationProblem() {
        override fun toString(): String = "sdkKey is not of the form ffc_<env>_<random>."
    }

    public class SdkKeyEnvironmentMismatch(
        public val keyEnvironment: String,
        public val configured: String,
    ) : ConfigurationProblem() {
        override fun toString(): String =
            "sdkKey is scoped to environment '$keyEnvironment' but the configuration asks for " +
                "'$configured'. The server will reject this; fix the key or the environment."
    }

    public object InsecureBaseUrl : ConfigurationProblem() {
        override fun toString(): String = "baseUrl must use https (or set allowsInsecureLocalTransport for loopback)."
    }

    public class InsecureTransportOnNonLoopbackHost(
        public val host: String,
    ) : ConfigurationProblem() {
        override fun toString(): String =
            "allowsInsecureLocalTransport applies to loopback (and the emulator's 10.0.2.2) only, not '$host'."
    }

    public object SignatureRequiredButNoTrustedKeys : ConfigurationProblem() {
        override fun toString(): String =
            "signaturePolicy is Required but no trusted keys were supplied, so every payload " +
                "will be rejected. Supply keys, or choose Disabled explicitly for local development."
    }

    public class RefreshIntervalTooShort(
        public val minimumSeconds: Long,
    ) : ConfigurationProblem() {
        // A statement of fact, not a warning about a hypothetical: the control plane counts
        // per device and answers 429.
        override fun toString(): String =
            "refreshIntervalSeconds is below the $minimumSeconds-second minimum. The control " +
                "plane rate-limits per device and will answer HTTP 429; the SDK keeps serving " +
                "cached values, so the effect is stale flags rather than an error."
    }
}

/**
 * Everything the SDK needs to run. Immutable — hand it to [FortressFlag.start] and nothing
 * can mutate it underneath the SDK.
 */
public class Configuration(
    /**
     * The client SDK key, of the form `ffc_<env>_<random>`.
     *
     * **This is not a secret.** It ships in every copy of the app and `strings` recovers it.
     * It is read-only, scoped to one tenant and one environment, and revocable without an
     * app release. Never put a management token here — the client API will not accept one.
     *
     * It is rate-limited server-side per device (sized with headroom over
     * [MINIMUM_REFRESH_INTERVAL_SECONDS], so a client polling as fast as this SDK allows is
     * never limited) and per key (an abuse ceiling). Either answers HTTP 429, which this SDK
     * treats as a transient failure: the fetch fails, resolution continues from the durable
     * cache, and the server's `Retry-After` feeds the backoff. Nothing is thrown to your app.
     */
    public val sdkKey: String,
    /** Which environment's values to read. */
    public val environment: Environment,
    /** The client API base URL. Defaults to FortressFlag's edge. */
    public val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * Custom tags to send with every flag fetch, fixed at start; change them at runtime with
     * [FortressFlag.setTags]. Keys are 1–64 chars of `A–Z a–z 0–9 . _ -`; values at most 256
     * bytes; at most 32 tags including the built-ins the SDK adds automatically
     * (`appVersion`, `appBuild`, `osVersion`, `platform`, `sdkVersion` — reserved names).
     * Entries outside the limits are dropped with a logged warning, never an error.
     *
     * Tags transit on every request but are never stored by FortressFlag — the server
     * evaluates them statelessly and discards them. They still leave the device: prefer
     * stable, non-identifying values, and hash anything user-derived first.
     */
    public val tags: Map<String, String> = emptyMap(),
    /** How the SDK treats payload signatures. Defaults to Required. */
    public val signaturePolicy: SignaturePolicy = SignaturePolicy.Required(TrustedKeys.FORTRESSFLAG_PRODUCTION),
    /**
     * How often to poll for new values, in seconds. Jitter of ±20% is applied so a fleet of
     * devices does not synchronise into a thundering herd against a recovering backend.
     */
    public val refreshIntervalSeconds: Long = 300,
    /**
     * Per-request timeout in seconds. Short on purpose: a slow flag fetch must never become
     * the app's problem, and a timeout costs nothing because the cache answers immediately.
     */
    public val requestTimeoutSeconds: Long = 10,
    /**
     * Permits a plaintext `http://` base URL for loopback — and, on Android, for `10.0.2.2`,
     * the emulator's alias for its host machine, without which no local-dev story exists on
     * this platform. Every request made under it logs a warning, and any non-loopback host
     * is still refused.
     */
    public val allowsInsecureLocalTransport: Boolean = false,
    /** How much to log. */
    public val logging: LogPolicy = LogPolicy.STANDARD,
) {
    /**
     * Everything wrong with this configuration.
     *
     * Returned rather than thrown, and public, so a customer can assert on it in their own
     * test suite and find the mistake at build time. [FortressFlag.start] calls this and
     * logs, but never fails the app: an app that ships with a typo'd key still launches, it
     * just serves defaults.
     */
    public fun validate(): List<ConfigurationProblem> {
        val problems = mutableListOf<ConfigurationProblem>()

        if (sdkKey.isEmpty()) {
            problems.add(ConfigurationProblem.EmptySdkKey)
        } else {
            // limit = 3, NOT an unbounded split: the key's random part is base64url, whose
            // alphabet includes `_` — an unbounded split would mis-reject roughly three keys
            // in four, silently and intermittently (the backend's sdkkey.SplitN comment).
            val parts = sdkKey.split("_", limit = 3)
            if (parts.size != 3 || parts[0] != "ffc" || parts[1].isEmpty() || parts[2].isEmpty()) {
                problems.add(ConfigurationProblem.SdkKeyWrongFormat)
            } else if (parts[1] != environment.key) {
                problems.add(ConfigurationProblem.SdkKeyEnvironmentMismatch(parts[1], environment.key))
            }
        }

        val scheme = baseUrl.substringBefore("://", "").lowercase()
        if (scheme != "https") {
            if (allowsInsecureLocalTransport && scheme == "http") {
                val host = hostOf(baseUrl)
                if (host !in LOOPBACK_HOSTS) {
                    problems.add(ConfigurationProblem.InsecureTransportOnNonLoopbackHost(host))
                }
            } else {
                problems.add(ConfigurationProblem.InsecureBaseUrl)
            }
        }

        val policy = signaturePolicy
        if (policy is SignaturePolicy.Required && policy.trustedKeys.isEmpty) {
            problems.add(ConfigurationProblem.SignatureRequiredButNoTrustedKeys)
        }

        if (refreshIntervalSeconds < MINIMUM_REFRESH_INTERVAL_SECONDS) {
            problems.add(ConfigurationProblem.RefreshIntervalTooShort(MINIMUM_REFRESH_INTERVAL_SECONDS))
        }

        return problems
    }

    public companion object {
        /** FortressFlag's client API edge. */
        public const val DEFAULT_BASE_URL: String = "https://edge.fortressflag.com"

        /**
         * The shortest polling interval the SDK will honour. Anything faster is the caller
         * volunteering to be rate-limited, and costs the customer money for values that did
         * not change (Founding §6). The server's per-device limit is sized with headroom
         * over this number.
         */
        public const val MINIMUM_REFRESH_INTERVAL_SECONDS: Long = 30

        /**
         * `10.0.2.2` is in this set and the iOS equivalent's is not: it is the Android
         * emulator's fixed alias for the machine running it, and the emulator cannot see
         * `localhost` (that is the emulated device itself). Without it, the example app's
         * local-dev story fails "plaintext refused" in a way that looks like a backend bug.
         */
        internal val LOOPBACK_HOSTS: Set<String> = setOf("localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2")

        internal fun hostOf(url: String): String {
            val afterScheme = url.substringAfter("://", "")
            val authority = afterScheme.substringBefore("/")
            // Strip a port; keep bracketed IPv6 intact.
            return if (authority.startsWith("[")) {
                authority.substringBefore("]") + "]"
            } else {
                authority.substringBefore(":")
            }
        }
    }
}
