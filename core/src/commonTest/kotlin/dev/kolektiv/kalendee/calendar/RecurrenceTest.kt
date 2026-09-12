package dev.kolektiv.kalendee.calendar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

class RecurrenceTest {
    private val zone = TimeZone.UTC

    @Test
    fun dailyEveryTwoDaysFillsTheRange() {
        val event = series(
            start = Instant.parse("2026-09-07T14:00:00Z"),
            end = Instant.parse("2026-09-07T14:30:00Z"),
            Recurrence(frequency = RecurrenceFrequency.DAILY, interval = 2),
        )
        val range = InstantRange(
            start = Instant.parse("2026-09-07T00:00:00Z"),
            end = Instant.parse("2026-09-14T00:00:00Z"),
        )
        assertEquals(
            listOf(
                Instant.parse("2026-09-07T14:00:00Z"),
                Instant.parse("2026-09-09T14:00:00Z"),
                Instant.parse("2026-09-11T14:00:00Z"),
                Instant.parse("2026-09-13T14:00:00Z"),
            ),
            event.occurrencesIn(range, zone).map { it.start },
        )
    }

    @Test
    fun weeklyHonorsCount() {
        val event = series(
            start = Instant.parse("2026-09-07T15:00:00Z"),
            end = Instant.parse("2026-09-07T16:00:00Z"),
            Recurrence(frequency = RecurrenceFrequency.WEEKLY, interval = 1, count = 3),
        )
        val range = InstantRange(
            start = Instant.parse("2026-09-01T00:00:00Z"),
            end = Instant.parse("2026-10-01T00:00:00Z"),
        )
        assertEquals(3, event.occurrencesIn(range, zone).size)
    }

    @Test
    fun weeklyKeepsLocalTimeAcrossDst() {
        val zone = TimeZone.of("America/New_York")
        val event = series(
            start = Instant.parse("2026-03-06T15:00:00Z"),
            end = Instant.parse("2026-03-06T16:00:00Z"),
            Recurrence(frequency = RecurrenceFrequency.WEEKLY, interval = 1, count = 2),
        )
        val range = InstantRange(
            start = Instant.parse("2026-03-06T00:00:00Z"),
            end = Instant.parse("2026-03-20T00:00:00Z"),
        )
        assertEquals(
            listOf(
                Instant.parse("2026-03-06T15:00:00Z"),
                Instant.parse("2026-03-13T14:00:00Z"),
            ),
            event.occurrencesIn(range, zone).map { it.start },
        )
    }

    @Test
    fun untilIsExclusiveOfLaterOccurrences() {
        val event = series(
            start = Instant.parse("2026-09-07T10:00:00Z"),
            end = Instant.parse("2026-09-07T11:00:00Z"),
            Recurrence(
                frequency = RecurrenceFrequency.DAILY,
                interval = 1,
                until = Instant.parse("2026-09-09T10:00:00Z"),
            ),
        )
        val range = InstantRange(
            start = Instant.parse("2026-09-07T00:00:00Z"),
            end = Instant.parse("2026-09-14T00:00:00Z"),
        )
        assertEquals(
            listOf(
                Instant.parse("2026-09-07T10:00:00Z"),
                Instant.parse("2026-09-08T10:00:00Z"),
            ),
            event.occurrencesIn(range, zone).map { it.start },
        )
    }

    @Test
    fun splitAtSeriesStartReturnsWholeSeries() {
        val rule = Recurrence(frequency = RecurrenceFrequency.DAILY, interval = 1, count = 3)
        val start = Instant.parse("2026-09-07T10:00:00Z")
        val split = rule.splitAt(start, start, zone)
        val result = requireNotNull(split)
        assertEquals(0, result.occurrenceIndex)
        assertNull(result.truncated)
        assertEquals(rule, result.following)
    }

    @Test
    fun countBasedSplitTruncatesAndReplaysTheChain() {
        val rule = Recurrence(frequency = RecurrenceFrequency.DAILY, interval = 1, count = 5)
        val event = series(
            start = Instant.parse("2026-09-07T10:00:00Z"),
            end = Instant.parse("2026-09-07T11:00:00Z"),
            recurrence = rule,
        )
        val range = monthRange()
        val from = event.occurrencesIn(range, zone)[2].start
        val split = requireNotNull(rule.splitAt(event.start, from, zone))
        assertEquals(2, split.occurrenceIndex)
        assertEquals(2, split.truncated?.count)
        assertEquals(3, split.following.count)
        assertSplitReplays(event, split, range)
    }

    @Test
    fun untilBasedSplitKeepsTheOriginalBound() {
        val until = Instant.parse("2026-09-11T10:00:00Z")
        val rule = Recurrence(frequency = RecurrenceFrequency.DAILY, interval = 1, until = until)
        val event = series(
            start = Instant.parse("2026-09-07T10:00:00Z"),
            end = Instant.parse("2026-09-07T11:00:00Z"),
            recurrence = rule,
        )
        val range = monthRange()
        val from = event.occurrencesIn(range, zone)[2].start
        val split = requireNotNull(rule.splitAt(event.start, from, zone))
        assertEquals(2, split.occurrenceIndex)
        assertEquals(from, split.truncated?.until)
        assertEquals(rule, split.following)
        assertSplitReplays(event, split, range)
    }

    @Test
    fun infiniteWeeklySplitBoundsOnlyThePredecessor() {
        val rule = Recurrence(frequency = RecurrenceFrequency.WEEKLY, interval = 1)
        val event = series(
            start = Instant.parse("2026-09-07T10:00:00Z"),
            end = Instant.parse("2026-09-07T11:00:00Z"),
            recurrence = rule,
        )
        val range = InstantRange(
            start = Instant.parse("2026-09-01T00:00:00Z"),
            end = Instant.parse("2026-10-06T00:00:00Z"),
        )
        val from = event.occurrencesIn(range, zone)[2].start
        val split = requireNotNull(rule.splitAt(event.start, from, zone))
        assertEquals(2, split.occurrenceIndex)
        assertEquals(from, split.truncated?.until)
        assertEquals(rule, split.following)
        assertSplitReplays(event, split, range)
    }

    @Test
    fun splitHonorsBothCountAndUntil() {
        val until = Instant.parse("2026-09-10T10:00:00Z")
        val untilBound = Recurrence(
            frequency = RecurrenceFrequency.DAILY,
            interval = 1,
            until = until,
            count = 10,
        )
        val untilEvent = series(
            start = Instant.parse("2026-09-07T10:00:00Z"),
            end = Instant.parse("2026-09-07T11:00:00Z"),
            recurrence = untilBound,
        )
        val range = monthRange()
        val untilSplit = requireNotNull(
            untilBound.splitAt(untilEvent.start, untilEvent.occurrencesIn(range, zone)[1].start, zone),
        )
        assertEquals(1, untilSplit.occurrenceIndex)
        assertEquals(1, untilSplit.truncated?.count)
        assertEquals(until, untilSplit.truncated?.until)
        assertEquals(9, untilSplit.following.count)
        assertEquals(until, untilSplit.following.until)
        assertSplitReplays(untilEvent, untilSplit, range)

        val longUntil = Instant.parse("2026-09-20T10:00:00Z")
        val countBound = Recurrence(
            frequency = RecurrenceFrequency.DAILY,
            interval = 1,
            until = longUntil,
            count = 3,
        )
        val countEvent = series(
            start = Instant.parse("2026-09-07T10:00:00Z"),
            end = Instant.parse("2026-09-07T11:00:00Z"),
            recurrence = countBound,
        )
        val countSplit = requireNotNull(
            countBound.splitAt(countEvent.start, countEvent.occurrencesIn(range, zone)[1].start, zone),
        )
        assertEquals(1, countSplit.occurrenceIndex)
        assertEquals(1, countSplit.truncated?.count)
        assertEquals(2, countSplit.following.count)
        assertEquals(longUntil, countSplit.following.until)
        assertSplitReplays(countEvent, countSplit, range)
    }

    @Test
    fun splitRejectsNonOccurrences() {
        val start = Instant.parse("2026-09-07T10:00:00Z")
        val rule = Recurrence(frequency = RecurrenceFrequency.DAILY, interval = 2, count = 4)
        assertNull(rule.splitAt(start, Instant.parse("2026-09-08T10:00:00Z"), zone))
        assertNull(rule.splitAt(start, Instant.parse("2026-09-06T10:00:00Z"), zone))
        assertNull(rule.splitAt(start, Instant.parse("2026-09-15T10:00:00Z"), zone))

        val untilRule = Recurrence(
            frequency = RecurrenceFrequency.DAILY,
            interval = 1,
            until = Instant.parse("2026-09-09T10:00:00Z"),
        )
        assertNull(untilRule.splitAt(start, Instant.parse("2026-09-09T10:00:00Z"), zone))
    }

    @Test
    fun monthlySplitReanchorsTheMonthEndChain() {
        val rule = Recurrence(frequency = RecurrenceFrequency.MONTHLY, interval = 1, count = 4)
        val event = series(
            start = Instant.parse("2026-01-31T10:00:00Z"),
            end = Instant.parse("2026-01-31T11:00:00Z"),
            recurrence = rule,
        )
        val range = InstantRange(
            start = Instant.parse("2026-01-01T00:00:00Z"),
            end = Instant.parse("2026-06-01T00:00:00Z"),
        )
        val original = event.occurrencesIn(range, zone).map { it.start }
        assertEquals(
            listOf(
                Instant.parse("2026-01-31T10:00:00Z"),
                Instant.parse("2026-02-28T10:00:00Z"),
                Instant.parse("2026-03-28T10:00:00Z"),
                Instant.parse("2026-04-28T10:00:00Z"),
            ),
            original,
        )
        val split = requireNotNull(rule.splitAt(event.start, original[2], zone))
        assertEquals(2, split.occurrenceIndex)
        assertEquals(2, split.truncated?.count)
        assertEquals(2, split.following.count)
        assertSplitReplays(event, split, range)
    }

    @Test
    fun yearlySplitReanchorsTheLeapDayChain() {
        val rule = Recurrence(frequency = RecurrenceFrequency.YEARLY, interval = 1, count = 3)
        val event = series(
            start = Instant.parse("2024-02-29T10:00:00Z"),
            end = Instant.parse("2024-02-29T11:00:00Z"),
            recurrence = rule,
        )
        val range = InstantRange(
            start = Instant.parse("2024-01-01T00:00:00Z"),
            end = Instant.parse("2027-01-01T00:00:00Z"),
        )
        val original = event.occurrencesIn(range, zone).map { it.start }
        assertEquals(
            listOf(
                Instant.parse("2024-02-29T10:00:00Z"),
                Instant.parse("2025-02-28T10:00:00Z"),
                Instant.parse("2026-02-28T10:00:00Z"),
            ),
            original,
        )
        val split = requireNotNull(rule.splitAt(event.start, original[1], zone))
        assertEquals(1, split.occurrenceIndex)
        assertEquals(1, split.truncated?.count)
        assertEquals(2, split.following.count)
        assertSplitReplays(event, split, range)
    }

    @Test
    fun weeklySplitKeepsWallClockAcrossDst() {
        val newYork = TimeZone.of("America/New_York")
        val rule = Recurrence(frequency = RecurrenceFrequency.WEEKLY, interval = 1, count = 3)
        val event = series(
            start = Instant.parse("2026-03-06T15:00:00Z"),
            end = Instant.parse("2026-03-06T16:00:00Z"),
            recurrence = rule,
        )
        val range = InstantRange(
            start = Instant.parse("2026-03-01T00:00:00Z"),
            end = Instant.parse("2026-03-31T00:00:00Z"),
        )
        val original = event.occurrencesIn(range, newYork).map { it.start }
        assertEquals(
            listOf(
                Instant.parse("2026-03-06T15:00:00Z"),
                Instant.parse("2026-03-13T14:00:00Z"),
                Instant.parse("2026-03-20T14:00:00Z"),
            ),
            original,
        )
        val split = requireNotNull(rule.splitAt(event.start, original[1], newYork))
        assertEquals(1, split.occurrenceIndex)
        assertEquals(1, split.truncated?.count)
        assertEquals(2, split.following.count)
        assertSplitReplays(event, split, range, newYork)
    }

    private fun assertSplitReplays(
        event: Event,
        split: SeriesSplit,
        range: InstantRange,
        replayZone: TimeZone = zone,
    ) {
        val original = event.occurrencesIn(range, replayZone).map { it.start }
        val index = split.occurrenceIndex
        val truncated = requireNotNull(split.truncated)
        assertEquals(
            original.take(index),
            event.copy(recurrence = truncated).occurrencesIn(range, replayZone).map { it.start },
        )
        val following = event.copy(
            start = original[index],
            end = original[index] + (event.end - event.start),
            recurrence = split.following,
        )
        assertEquals(
            original.drop(index),
            following.occurrencesIn(range, replayZone).map { it.start },
        )
    }

    private fun monthRange() = InstantRange(
        start = Instant.parse("2026-09-01T00:00:00Z"),
        end = Instant.parse("2026-10-01T00:00:00Z"),
    )

    private fun series(start: Instant, end: Instant, recurrence: Recurrence) = Event(
        id = EventId.parse("00000000-0000-0000-0000-000000000001"),
        calendarId = CalendarId.parse("00000000-0000-0000-0000-000000000002"),
        title = "Repeat",
        start = start,
        end = end,
        allDay = false,
        status = EventStatus.CONFIRMED,
        recurrence = recurrence,
        etag = "etag",
        createdAt = start,
        updatedAt = start,
    )
}
