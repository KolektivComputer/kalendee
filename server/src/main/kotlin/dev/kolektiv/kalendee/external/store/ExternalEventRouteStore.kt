package dev.kolektiv.kalendee.external.store

import dev.kolektiv.kalendee.calendar.CalendarId
import kotlin.uuid.Uuid

/**
 * Where one external base event should be imported. A route either points at a
 * local calendar or explicitly skips the event.
 */
sealed interface RouteTarget {
    data class Calendar(val calendarId: CalendarId) : RouteTarget

    data object Skip : RouteTarget
}

/**
 * Per-event routing for a mirrored external calendar source. Events without a
 * route fall back to the source's default calendar.
 */
interface ExternalEventRouteStore {
    suspend fun routes(externalCalendarId: Uuid): Map<String, RouteTarget>

    suspend fun replaceRoutes(externalCalendarId: Uuid, routes: Map<String, RouteTarget>)

    suspend fun deleteRoutes(externalCalendarId: Uuid)
}
