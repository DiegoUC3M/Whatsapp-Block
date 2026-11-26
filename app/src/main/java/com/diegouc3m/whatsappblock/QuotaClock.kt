package com.diegouc3m.whatsappblock

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

internal data class UsageSlice(val atMillis: Long, val durationMs: Long)

/** Duration uses elapsedRealtime; wall time only assigns a verified interval to its hour/day. */
internal class QuotaClock(elapsedMillis: Long, wallMillis: Long) {
    private var previousElapsed = elapsedMillis
    private var previousWall = wallMillis

    fun sample(elapsedMillis: Long, wallMillis: Long): List<UsageSlice> {
        val duration = elapsedMillis - previousElapsed
        val wallDelta = wallMillis - previousWall
        val start = previousWall
        previousElapsed = elapsedMillis
        previousWall = wallMillis
        // A delayed callback cannot establish that the chat stayed visible during the gap.
        // A changed device clock must not consume quota or create negative time.
        if (duration !in 1..MAX_VERIFIED_GAP_MS || abs(wallDelta - duration) > CLOCK_TOLERANCE_MS) {
            return emptyList()
        }
        return splitAtHours(start, duration)
    }

    companion object {
        private const val MAX_VERIFIED_GAP_MS = 2_000L
        private const val CLOCK_TOLERANCE_MS = 250L

        internal fun splitAtHours(startMillis: Long, durationMs: Long): List<UsageSlice> {
            if (durationMs <= 0) return emptyList()
            val result = mutableListOf<UsageSlice>()
            val zone = ZoneId.systemDefault()
            var cursor = startMillis
            val end = startMillis + durationMs
            while (cursor < end) {
                val instant = Instant.ofEpochMilli(cursor)
                val local = instant.atZone(zone)
                val nextHour = local.toLocalDateTime().truncatedTo(ChronoUnit.HOURS).plusHours(1)
                    .toInstant(local.offset).toEpochMilli()
                // An offset change can move the local clock before the next :00.
                val transition = zone.rules.nextTransition(instant)?.instant?.toEpochMilli()
                val stop = minOf(end, nextHour, transition ?: end)
                result.add(UsageSlice(cursor, stop - cursor))
                cursor = stop
            }
            return result
        }
    }
}
