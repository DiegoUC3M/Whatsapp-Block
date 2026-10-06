package com.diegouc3m.whatsappblock

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class QuotaClockTest {
    private lateinit var previousZone: TimeZone

    @Before fun useUtc() {
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }
    @After fun restoreZone() = TimeZone.setDefault(previousZone)

    @Test fun normalTicksUseMonotonicDuration() {
        val start = instant(10, 10, 0)
        val clock = QuotaClock(5_000, start)
        assertEquals(listOf(UsageSlice(start, 1_000)), clock.sample(6_000, start + 1_000))
        assertEquals(1_000L, clock.sample(7_000, start + 2_000).sumOf { it.durationMs })
    }

    @Test fun deviceClockChangesCannotInflateOrSubtractUsage() {
        val start = instant(10, 10, 0)
        val clock = QuotaClock(5_000, start)
        assertTrue(clock.sample(6_000, start + 3_601_000).isEmpty())
        assertTrue(clock.sample(7_000, start - 3_600_000).isEmpty())
        assertEquals(1_000L, clock.sample(8_000, start - 3_599_000).sumOf { it.durationMs })
    }

    @Test fun suspendedOrDelayedCallbacksDoNotChargeAnUnverifiedGap() {
        val start = instant(10, 10, 0)
        val clock = QuotaClock(0, start)
        assertTrue(clock.sample(60_000, start + 60_000).isEmpty())
        assertEquals(1_000L, clock.sample(61_000, start + 61_000).sumOf { it.durationMs })
    }

    @Test fun eachSideOfTheHourUsesItsOwnQuotaBucket() {
        val start = instant(10, 59, 59) + 500
        val clock = QuotaClock(0, start)
        assertEquals(listOf(UsageSlice(start, 500), UsageSlice(start + 500, 500)),
            clock.sample(1_000, start + 1_000))
    }

    @Test fun midnightSeparatesBothHourlyAndDailyBuckets() {
        val start = instant(23, 59, 59) + 800
        assertEquals(listOf(UsageSlice(start, 200), UsageSlice(start + 200, 800)),
            QuotaClock(0, start).sample(1_000, start + 1_000))
    }

    @Test fun duplicateAndOutOfOrderTicksDoNotConsumeQuota() {
        val start = instant(10, 10, 0)
        val clock = QuotaClock(5_000, start)
        assertTrue(clock.sample(5_000, start).isEmpty())
        assertTrue(clock.sample(4_000, start - 1_000).isEmpty())
    }

    private fun instant(hour: Int, minute: Int, second: Int): Long = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.OCTOBER, 6, hour, minute, second)
    }.timeInMillis
}
