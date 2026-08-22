package com.fortressflag.sdk

/**
 * Where a resolved value came from. Reported so a developer can tell "on because the server
 * said so" from "on because that is what this device last heard" — a distinction that
 * matters a great deal when debugging a rollout.
 */
public enum class ValueSource {
    /** From the most recent successful fetch. */
    FRESH,

    /** From the durable cache: the last value this device actually saw. */
    CACHED,

    /** The default the caller passed. */
    DEVELOPER_DEFAULT,

    /** `false`. Nothing has ever been recorded for this flag on this device. */
    SAFE_DEFAULT,
}

/** A resolved flag value and its provenance. */
public class Resolution(
    public val value: FlagValue,
    public val source: ValueSource,
) {
    override fun equals(other: Any?): Boolean = other is Resolution && other.value == value && other.source == source

    override fun hashCode(): Int = 31 * value.hashCode() + source.hashCode()
}

/**
 * What happened on a refresh. Returned rather than thrown — no SDK call throws to the
 * caller (Founding §8.1).
 */
public sealed class RefreshOutcome {
    /** New values arrived. [changedKeys] lists the flags whose *effective* value moved,
     * which is not the same as the flags present in the payload. */
    public class Updated(
        public val changedKeys: Set<String>,
    ) : RefreshOutcome()

    /** The server confirmed nothing changed, or the payload matched what we already had. */
    public object Unchanged : RefreshOutcome()

    public class Failed(
        public val failure: RefreshFailure,
    ) : RefreshOutcome()

    /** `start` has not been called. */
    public object NotStarted : RefreshOutcome()
}

/**
 * Why a refresh did not produce new values. Deliberately coarse and stable: this is a public
 * type, so it names *classes* of failure a caller might act on, not every internal rejection
 * reason. The detail goes to the log.
 */
public enum class RefreshFailure {
    NETWORK,
    UNAUTHORIZED,
    RATE_LIMITED,
    SERVER,

    /** A payload arrived but failed verification — bad signature, wrong environment, wrong
     * device, expired, or a contract version this SDK does not understand. */
    REJECTED_PAYLOAD,
}

/** A read-only view of what the SDK is doing, for a customer's own diagnostics screen. */
public class Diagnostics(
    public val isStarted: Boolean,
    /** The pseudonymous device identifier, if one has been minted. Safe to display and to
     * include in a support ticket — it is random and identifies nobody. */
    public val deviceIdentity: String?,
    /** Epoch millis of the last successful conversation with the server (a 304 counts). */
    public val lastSuccessfulFetchEpochMillis: Long?,
    /** Number of flags in the most recent accepted payload. */
    public val freshFlagCount: Int,
    /** Number of flags in the durable cache. */
    public val cachedFlagCount: Int,
    /** The KEYS of the tags each fetch sends, sorted. Keys only, never values: the keys are
     * the integrator's own configuration; the values are not exposed anywhere. */
    public val sentTagKeys: List<String>,
)
