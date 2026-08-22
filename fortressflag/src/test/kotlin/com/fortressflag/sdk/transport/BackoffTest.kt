package com.fortressflag.sdk.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    private val noJitter: (Double, Double) -> Double = { _, _ -> 0.0 }

    @Test
    fun doublesFromBaseAndCaps() {
        val backoff = Backoff()
        assertEquals(0.0, backoff.retryDelaySeconds(0, noJitter), 0.0)
        assertEquals(2.0, backoff.retryDelaySeconds(1, noJitter), 0.0)
        assertEquals(4.0, backoff.retryDelaySeconds(2, noJitter), 0.0)
        assertEquals(256.0, backoff.retryDelaySeconds(8, noJitter), 0.0)
        assertEquals(1800.0, backoff.retryDelaySeconds(11, noJitter), 0.0)
        // A device offline for a very long time keeps the cap — no overflow, no wrap.
        assertEquals(1800.0, backoff.retryDelaySeconds(10_000, noJitter), 0.0)
    }

    @Test
    fun jitterStaysWithinTwentyPercentBothSides() {
        val backoff = Backoff()
        val low = backoff.retryDelaySeconds(3) { a, _ -> a }
        val high = backoff.retryDelaySeconds(3) { _, b -> b }
        assertEquals(8.0 * 0.8, low, 1e-9)
        assertEquals(8.0 * 1.2, high, 1e-9)
        // Jitter applies on the SUCCESS path too: a fleet retrying in lockstep after an
        // outage is the failure mode de-synchronisation exists to prevent.
        val pollLow = backoff.pollDelaySeconds(300.0) { a, _ -> a }
        val pollHigh = backoff.pollDelaySeconds(300.0) { _, b -> b }
        assertEquals(240.0, pollLow, 1e-9)
        assertEquals(360.0, pollHigh, 1e-9)
    }

    @Test
    fun retryAfterIsHonouredButNeverPastTheCap() {
        val backoff = Backoff()
        assertEquals(90.0, backoff.retryDelaySeconds(90.0, 1, noJitter), 0.0)
        // A hostile Retry-After of a year must not silently disable flag updates until the
        // app is force-quit.
        assertEquals(1800.0, backoff.retryDelaySeconds(31_536_000.0, 1, noJitter), 0.0)
        // Absent or nonsense Retry-After falls back to the exponential path.
        assertEquals(2.0, backoff.retryDelaySeconds(null, 1, noJitter), 0.0)
        assertEquals(2.0, backoff.retryDelaySeconds(-5.0, 1, noJitter), 0.0)
    }

    @Test
    fun systemRandomStaysInRange() {
        repeat(100) {
            val value = Backoff.SYSTEM_RANDOM(-1.0, 1.0)
            assertTrue(value >= -1.0 && value < 1.0)
        }
        assertEquals(0.0, Backoff.SYSTEM_RANDOM(0.0, 0.0), 0.0)
    }
}
