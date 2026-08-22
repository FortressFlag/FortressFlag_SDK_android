package com.fortressflag.sdk.transport

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * How long to wait before the next poll.
 *
 * Two jobs, and the second matters at scale. The obvious job is to stop a device hammering
 * a failing backend. The less obvious job is **de-synchronisation**: without jitter, every
 * device that started polling at the same moment — which, after an outage, is all of them —
 * retries in lockstep, and the backend that just came back up is knocked over by its own
 * clients. Jitter on the success path matters as much as on the failure path.
 *
 * Randomness is injected so the bounds can be asserted in tests instead of hoped for.
 */
internal class Backoff(
    /** Delay after the first failure, in seconds. Doubles from here. */
    private val baseSeconds: Double = 2.0,
    /**
     * Ceiling, in seconds. Half an hour: a device that has been failing for hours is almost
     * certainly offline, and there is nothing to gain from asking more often — the cache is
     * already answering every call.
     */
    val capSeconds: Double = 1800.0,
    /** Fraction of the computed delay to spread over, either side. 0.2 gives ±20%. */
    private val jitterFraction: Double = 0.2,
) {
    /** Delay in seconds before retrying after [consecutiveFailures] failures in a row. */
    fun retryDelaySeconds(
        consecutiveFailures: Int,
        random: (Double, Double) -> Double = SYSTEM_RANDOM,
    ): Double {
        if (consecutiveFailures <= 0) return 0.0
        // Exponent capped before `pow` so a long-lived offline device cannot overflow the
        // multiplier into infinity on its ten-thousandth failed attempt.
        val exponent = min(consecutiveFailures - 1, 32).toDouble()
        val raw = min(baseSeconds * 2.0.pow(exponent), capSeconds)
        return jittered(raw, random)
    }

    /** Delay in seconds before the next routine poll. */
    fun pollDelaySeconds(
        intervalSeconds: Double,
        random: (Double, Double) -> Double = SYSTEM_RANDOM,
    ): Double = jittered(intervalSeconds, random)

    /**
     * A server that told us when to come back is obeyed, but never past the cap — a hostile
     * or misconfigured `Retry-After` of a year must not silently disable flag updates for a
     * device until the app is force-quit.
     */
    fun retryDelaySeconds(
        retryAfterSeconds: Double?,
        consecutiveFailures: Int,
        random: (Double, Double) -> Double = SYSTEM_RANDOM,
    ): Double {
        if (retryAfterSeconds == null || retryAfterSeconds <= 0) {
            return retryDelaySeconds(consecutiveFailures, random)
        }
        return min(retryAfterSeconds, capSeconds)
    }

    private fun jittered(
        seconds: Double,
        random: (Double, Double) -> Double,
    ): Double {
        val spread = seconds * jitterFraction
        return seconds + random(-spread, spread)
    }

    companion object {
        /** The production source of randomness. */
        val SYSTEM_RANDOM: (Double, Double) -> Double = { low, high ->
            if (low >= high) low else Random.nextDouble(low, high)
        }
    }
}
