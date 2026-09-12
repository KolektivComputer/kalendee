package dev.kolektiv.kalendee.calendar

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class InstantRangeTest {
    private val range = InstantRange(
        start = Instant.parse("2026-03-01T10:00:00Z"),
        end = Instant.parse("2026-03-01T12:00:00Z"),
    )

    @Test
    fun overlapsWhenEventCoversRangeStart() {
        assertTrue(
            range.overlaps(
                eventStart = Instant.parse("2026-03-01T09:00:00Z"),
                eventEnd = Instant.parse("2026-03-01T11:00:00Z"),
            ),
        )
    }

    @Test
    fun overlapsWhenEventIsContained() {
        assertTrue(
            range.overlaps(
                eventStart = Instant.parse("2026-03-01T10:30:00Z"),
                eventEnd = Instant.parse("2026-03-01T11:00:00Z"),
            ),
        )
    }

    @Test
    fun doesNotOverlapWhenEventEndsAtRangeStart() {
        assertFalse(
            range.overlaps(
                eventStart = Instant.parse("2026-03-01T09:00:00Z"),
                eventEnd = Instant.parse("2026-03-01T10:00:00Z"),
            ),
        )
    }

    @Test
    fun doesNotOverlapWhenEventStartsAtRangeEnd() {
        assertFalse(
            range.overlaps(
                eventStart = Instant.parse("2026-03-01T12:00:00Z"),
                eventEnd = Instant.parse("2026-03-01T13:00:00Z"),
            ),
        )
    }
}
