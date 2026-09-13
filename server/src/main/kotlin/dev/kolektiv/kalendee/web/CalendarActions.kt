package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.OptionalField
import dev.kolektiv.kalendee.calendar.OrganizationId
import dev.kolektiv.kalendee.calendar.OrganizationTeamId
import dev.kolektiv.kalendee.calendar.UpdateCalendar
import dev.kolektiv.keel.KeelAction

class CalendarActions(
    private val store: CalendarStore,
    private val auth: AuthService,
    private val settings: AuthSettings,
    private val syncInfo: CalendarSyncInfoEnricher,
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
        ).toSummary(auth).withSyncInfo()
    }

    @KeelAction("kalendee.setCalendarHidden")
    suspend fun setHidden(input: SetCalendarHiddenIn): CalendarSummary = mapDomainErrors("id") {
        val user = requireSessionUser(auth, settings)
        store.setCalendarHidden(CalendarId.parse(input.id), user.id, input.hidden)?.toSummary(auth)
            ?.withSyncInfo()
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
        )?.toSummary(auth)?.withSyncInfo() ?: throw CalendarException.NotFound("calendar not found")
    }

    @KeelAction("kalendee.transferCalendar")
    suspend fun transfer(input: TransferCalendarIn): CalendarSummary = mapDomainErrors("organizationId") {
        val user = requireSessionUser(auth, settings)
        store.transferCalendar(
            CalendarId.parse(input.id),
            user.id,
            input.organizationId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(OrganizationId::parse),
            input.teamId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(OrganizationTeamId::parse),
        )?.toSummary(auth)?.withSyncInfo() ?: throw CalendarException.NotFound("calendar not found")
    }

    @KeelAction("kalendee.deleteCalendar")
    suspend fun delete(input: DeleteCalendarIn): DeletedOut = mapDomainErrors("id") {
        val user = requireSessionUser(auth, settings)
        if (!store.deleteCalendar(CalendarId.parse(input.id), user.id)) {
            throw CalendarException.NotFound("calendar not found")
        }
        DeletedOut()
    }

    private suspend fun CalendarSummary.withSyncInfo(): CalendarSummary =
        syncInfo.attachSyncInfo(listOf(this)).single()
}
