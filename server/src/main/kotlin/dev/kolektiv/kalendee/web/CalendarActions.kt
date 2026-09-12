package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.OptionalField
import dev.kolektiv.kalendee.calendar.OrganizationId
import dev.kolektiv.kalendee.calendar.UpdateCalendar
import dev.kolektiv.keel.KeelAction

class CalendarActions(
    private val store: CalendarStore,
    private val auth: AuthService,
    private val settings: AuthSettings,
) {
    @KeelAction("kalendee.createCalendar")
    suspend fun create(input: CreateCalendarIn): CalendarSummary = mapDomainErrors("displayName") {
        val user = requireSessionUser(auth, settings)
        store.createCalendar(
            user.id,
            CreateCalendar(
                displayName = input.displayName,
                description = input.description?.trim()?.ifEmpty { null },
                timeZone = input.timeZone?.trim()?.ifEmpty { null },
                color = input.color,
                organizationId = input.organizationId
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(OrganizationId::parse),
            ),
        ).toSummary(auth)
    }

    @KeelAction("kalendee.setCalendarHidden")
    suspend fun setHidden(input: SetCalendarHiddenIn): CalendarSummary = mapDomainErrors("id") {
        val user = requireSessionUser(auth, settings)
        store.setCalendarHidden(CalendarId.parse(input.id), user.id, input.hidden)?.toSummary(auth)
            ?: throw CalendarException.NotFound("calendar not found")
    }

    @KeelAction("kalendee.updateCalendar")
    suspend fun update(input: UpdateCalendarIn): CalendarSummary = mapDomainErrors("displayName") {
        val user = requireSessionUser(auth, settings)
        store.updateCalendar(
            CalendarId.parse(input.id),
            user.id,
            UpdateCalendar(
                displayName = input.displayName,
                description = OptionalField.Present(input.description?.trim()?.ifEmpty { null }),
                timeZone = input.timeZone,
                color = input.color,
            ),
        )?.toSummary(auth) ?: throw CalendarException.NotFound("calendar not found")
    }

    @KeelAction("kalendee.deleteCalendar")
    suspend fun delete(input: DeleteCalendarIn): DeletedOut = mapDomainErrors("id") {
        val user = requireSessionUser(auth, settings)
        if (!store.deleteCalendar(CalendarId.parse(input.id), user.id)) {
            throw CalendarException.NotFound("calendar not found")
        }
        DeletedOut()
    }
}
