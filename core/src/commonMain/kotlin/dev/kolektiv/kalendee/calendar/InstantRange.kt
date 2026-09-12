package dev.kolektiv.kalendee.calendar

import kotlin.time.Instant

data class InstantRange(
    val start: Instant,
    val end: Instant,
) {
    fun overlaps(eventStart: Instant, eventEnd: Instant): Boolean =
        eventStart < end && eventEnd > start
}
