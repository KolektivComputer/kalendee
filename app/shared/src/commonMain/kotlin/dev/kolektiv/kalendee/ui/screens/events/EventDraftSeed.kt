package dev.kolektiv.kalendee.ui.screens.events

import kotlin.time.Instant

/**
 * One-shot hand-off for the tapped grid slot. The `Route.EventEditor` signature carries no
 * date/time, so [dev.kolektiv.kalendee.ui.screens.calendar.CalendarScreen] offers the slot
 * right before navigating and the editor takes it exactly once. UI-thread only.
 */
object EventDraftSeed {
    private var start: Instant? = null
    private var end: Instant? = null

    fun offer(start: Instant, end: Instant) {
        this.start = start
        this.end = end
    }

    fun clear() {
        start = null
        end = null
    }

    fun take(): Pair<Instant, Instant>? {
        val from = start ?: return null
        val to = end ?: return null
        clear()
        return from to to
    }
}
