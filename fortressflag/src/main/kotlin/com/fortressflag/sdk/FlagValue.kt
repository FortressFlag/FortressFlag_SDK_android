package com.fortressflag.sdk

/**
 * A flag's value: the contract-v2 union (backend ADR-0008, published in
 * `FortressFlag_Standards/contracts/contract-v2.md`).
 *
 * A flag has an immutable KIND — boolean, string or number — and every value it ever serves
 * is a bare scalar of that kind. There are deliberately no objects or arrays: evaluated
 * payloads are readable by anyone holding the customer's binary, and structured payloads
 * would force a schema conversation between app versions that a flag should never require.
 *
 * Under contract v1 every value was a boolean; a v1 payload decodes into [Bool] cases, which
 * is what lets a pre-v2 cache file load unchanged after an SDK upgrade.
 */
public sealed class FlagValue {
    public class Bool(
        public val value: Boolean,
    ) : FlagValue() {
        override fun equals(other: Any?): Boolean = other is Bool && other.value == value

        override fun hashCode(): Int = value.hashCode()
    }

    public class Str(
        public val value: String,
    ) : FlagValue() {
        override fun equals(other: Any?): Boolean = other is Str && other.value == value

        override fun hashCode(): Int = value.hashCode()
    }

    /** Numbers are float64 on the wire, exactly as the server serves them. */
    public class Num(
        public val value: Double,
    ) : FlagValue() {
        override fun equals(other: Any?): Boolean = other is Num && other.value == value

        override fun hashCode(): Int = value.hashCode()
    }

    /**
     * The boolean, or null when this value is not a boolean. `isEnabled` uses this for its
     * kind projection: a non-boolean value resolves to the caller's default — never a
     * coercion and never an error (Founding §8.1).
     */
    public val boolValue: Boolean? get() = (this as? Bool)?.value

    public val stringValue: String? get() = (this as? Str)?.value

    public val numberValue: Double? get() = (this as? Num)?.value
}
