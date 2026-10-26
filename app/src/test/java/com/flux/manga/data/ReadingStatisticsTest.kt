package com.flux.manga.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReadingStatisticsTest {
    private val utc = ZoneId.of("UTC")
    private fun instant(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun pausesAndRepeatedCheckpointsDoNotAccumulateBackgroundTime() {
        val clock = ReadingClock()
        val wall = instant("2026-10-23T12:00:00Z")
        clock.update(false, 0, wall, utc)
        clock.update(true, 1000, wall + 1000, utc)
        clock.update(true, 11_000, wall + 11_000, utc)
        assertEquals(10_000L, clock.snapshot().values.sum())
        clock.update(false, 12_000, wall + 12_000, utc)
        clock.update(false, 80_000, wall + 80_000, utc)
        clock.update(true, 90_000, wall + 90_000, utc)
        clock.update(false, 92_000, wall + 92_000, utc)
        assertEquals(mapOf("2026-10-23" to 13_000L), clock.snapshot())
    }

    @Test fun midnightSplitsWithoutRoundingAwaySeconds() {
        assertEquals(mapOf("2026-10-23" to 500L, "2026-10-24" to 1500L),
            splitReadingDays(instant("2026-10-23T23:59:59.500Z"), 2000, utc))
    }

    @Test fun daylightSavingDaysAreNotAlways24Hours() {
        val zone = ZoneId.of("America/New_York")
        assertEquals(mapOf("2026-03-08" to 23 * 3600_000L, "2026-03-09" to 3600_000L),
            splitReadingDays(instant("2026-03-08T05:00:00Z"), 24 * 3600_000L, zone))
        assertEquals(mapOf("2026-11-01" to 25 * 3600_000L),
            splitReadingDays(instant("2026-11-01T04:00:00Z"), 25 * 3600_000L, zone))
    }

    @Test fun wallClockJumpDoesNotInflateElapsedDuration() {
        val clock = ReadingClock()
        clock.update(true, 0, instant("2026-10-23T12:00:00Z"), utc)
        clock.update(true, 10_000, instant("2026-11-23T12:00:00Z"), utc)
        clock.update(false, 12_000, instant("2026-11-23T12:00:02Z"), utc)
        assertEquals(mapOf("2026-10-23" to 10_000L, "2026-11-23" to 2000L), clock.snapshot())
    }

    @Test fun timeZoneChangesApplyOnlyToNewIntervals() {
        val clock = ReadingClock()
        val wall = instant("2026-10-23T20:00:00Z")
        clock.update(true, 0, wall, utc)
        clock.update(true, 1000, wall + 1000, ZoneId.of("Asia/Shanghai"))
        clock.update(false, 3000, wall + 3000, ZoneId.of("Asia/Shanghai"))
        assertEquals(mapOf("2026-10-23" to 1000L, "2026-10-24" to 2000L), clock.snapshot())
    }

    @Test fun snapshotIsNotASharedMutableAccumulator() {
        val clock = ReadingClock()
        clock.update(true, 0, 0, utc)
        clock.update(true, 1000, 1000, utc)
        val first = clock.snapshot()
        clock.update(false, 2000, 2000, utc)
        assertEquals(1000L, first.values.sum())
        assertEquals(2000L, clock.snapshot().values.sum())
    }

    @Test fun retryAndOutOfOrderSnapshotsCannotDoubleCount() {
        val row = ReadingTimeRow("session", "2026-10-23", "online", "source", "1", "Same title", 10_000)
        assertTrue(newerReadingCheckpoint(null, row))
        assertFalse(newerReadingCheckpoint(row, row))
        assertFalse(newerReadingCheckpoint(row, row.copy(durationMs = 1000)))
        assertTrue(newerReadingCheckpoint(row, row.copy(durationMs = 12_000)))
    }

    @Test fun checkpointCannotChangeItsWorkIdentity() {
        val row = ReadingTimeRow("session", "2026-10-23", "online", "source", "1", "Same title", 10_000)
        listOf(row.copy(kind = "local"), row.copy(sourceId = "other"), row.copy(mangaId = "2")).forEach {
            assertThrows(IllegalArgumentException::class.java) { newerReadingCheckpoint(row, it) }
        }
    }

    @Test fun emptyAndShortDurationsStayHonest() {
        assertEquals(emptyMap<String, Long>(), splitReadingDays(0, 0, utc))
        assertEquals("不足 1 秒", readingDuration(1))
        assertEquals("0 秒", readingDuration(0))
        assertEquals("1 分 1 秒", readingDuration(61_000))
        assertEquals("1 小时 1 分", readingDuration(3660_000))
    }
}
