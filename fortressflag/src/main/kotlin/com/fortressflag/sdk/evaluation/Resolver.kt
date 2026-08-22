package com.fortressflag.sdk.evaluation

import com.fortressflag.sdk.FlagValue
import com.fortressflag.sdk.Resolution
import com.fortressflag.sdk.ValueSource

/**
 * The fallback cascade (Founding CLAUDE.md §8.4).
 *
 * Deliberately pure — no I/O, no clock, no state. This is the most consequential logic in
 * the SDK and the piece most likely to be got subtly wrong, so it is written to be
 * exhaustively table-testable in isolation.
 *
 * The order that is easy to get wrong: **a developer-supplied default substitutes for
 * `false` only.** A value this device actually received always beats a compiled-in guess,
 * even if that value is weeks old and the device has been offline since. The server is the
 * authority on a flag's state; the default only answers "what if this device has never heard
 * anything at all?".
 *
 * Within this function, a present fresh payload with the key absent falls through to the
 * cache tier. Do not design against that as a grace period: in the shipped SDK it never
 * happens across polls, because the client mirrors every accepted payload into both tiers —
 * so a key that leaves the payload leaves the cache on the same poll and resolves to the
 * developer default, else `false`. That is deliberate (backend ADR-0003): archiving a flag
 * turns the feature off on every online device within one poll, and a cache that
 * "helpfully" preserved removed keys would make deletion unable to end a rollout.
 */
internal object Resolver {
    fun resolve(
        key: String,
        fresh: Map<String, FlagValue>?,
        cached: Map<String, FlagValue>?,
        developerDefault: FlagValue?,
    ): Resolution {
        fresh?.get(key)?.let { return Resolution(it, ValueSource.FRESH) }
        cached?.get(key)?.let { return Resolution(it, ValueSource.CACHED) }
        developerDefault?.let { return Resolution(it, ValueSource.DEVELOPER_DEFAULT) }
        return Resolution(FlagValue.Bool(false), ValueSource.SAFE_DEFAULT)
    }

    /**
     * Effective values for every key this device knows about — the union of the fresh and
     * cached key sets, each resolved through [resolve] so enumeration cannot drift from
     * single-key reads. No developer default participates: enumeration reports what the
     * device *has*, and a compiled-in default is not something the device has.
     */
    fun resolveAll(
        fresh: Map<String, FlagValue>?,
        cached: Map<String, FlagValue>?,
    ): Map<String, Resolution> {
        val keys = HashSet<String>((fresh?.size ?: 0) + (cached?.size ?: 0))
        fresh?.keys?.let(keys::addAll)
        cached?.keys?.let(keys::addAll)
        val resolutions = HashMap<String, Resolution>(keys.size)
        for (key in keys) {
            resolutions[key] = resolve(key, fresh, cached, developerDefault = null)
        }
        return resolutions
    }

    /**
     * Keys whose effective value differs between two payload states, for change
     * notification. Computed over the union of both key sets so that a flag *disappearing*
     * from a payload is evaluated too: it falls back down the cascade, which may well change
     * its effective value.
     */
    fun changedKeys(
        oldFresh: Map<String, FlagValue>?,
        newFresh: Map<String, FlagValue>?,
        cached: Map<String, FlagValue>?,
    ): Set<String> {
        val keys = HashSet<String>((oldFresh?.size ?: 0) + (newFresh?.size ?: 0))
        oldFresh?.keys?.let(keys::addAll)
        newFresh?.keys?.let(keys::addAll)
        return keys.filterTo(HashSet()) { key ->
            val before = resolve(key, oldFresh, cached, developerDefault = null)
            val after = resolve(key, newFresh, cached, developerDefault = null)
            before.value != after.value
        }
    }
}
