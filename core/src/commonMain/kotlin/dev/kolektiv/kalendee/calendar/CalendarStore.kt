package dev.kolektiv.kalendee.calendar

import dev.kolektiv.kalendee.auth.UserId
import kotlin.time.Instant

interface CalendarStore {
    suspend fun ping()

    suspend fun listCalendars(userId: UserId): List<Calendar>
    suspend fun getCalendar(id: CalendarId, userId: UserId): Calendar?
    suspend fun createCalendar(ownerId: UserId, command: CreateCalendar): Calendar
    suspend fun updateCalendar(id: CalendarId, ownerId: UserId, command: UpdateCalendar): Calendar?
    suspend fun deleteCalendar(id: CalendarId, ownerId: UserId): Boolean
    suspend fun setCalendarHidden(id: CalendarId, userId: UserId, hidden: Boolean): Calendar?
    suspend fun transferCalendar(
        id: CalendarId,
        actorId: UserId,
        destinationOrganizationId: OrganizationId?,
        destinationTeamId: OrganizationTeamId? = null,
    ): Calendar?

    suspend fun listShares(calendarId: CalendarId, ownerId: UserId): List<CalendarShare>
    suspend fun addShare(
        calendarId: CalendarId,
        ownerId: UserId,
        inviteeId: UserId,
        permission: CalendarPermission,
    ): CalendarShare

    suspend fun updateShare(
        calendarId: CalendarId,
        ownerId: UserId,
        inviteeId: UserId,
        permission: CalendarPermission,
    ): CalendarShare?

    suspend fun removeShare(calendarId: CalendarId, ownerId: UserId, inviteeId: UserId): Boolean
    suspend fun setPublicLink(calendarId: CalendarId, ownerId: UserId, enabled: Boolean): Calendar?
    suspend fun rotatePublicLink(calendarId: CalendarId, ownerId: UserId): Calendar?
    suspend fun publicCalendar(token: String): Calendar?
    suspend fun listPublicEvents(calendarId: CalendarId, range: InstantRange? = null): List<Event>
    suspend fun follow(token: String, userId: UserId): Calendar?
    suspend fun unfollow(calendarId: CalendarId, userId: UserId): Boolean
    suspend fun countFollowers(calendarId: CalendarId): Int

    suspend fun holidayPrefs(ownerId: UserId): HolidayPrefs
    suspend fun setShowHolidays(ownerId: UserId, show: Boolean): HolidayPrefs
    suspend fun setHolidaySubscriptions(ownerId: UserId, holidayIds: List<String>): HolidayPrefs
    suspend fun createCustomHoliday(ownerId: UserId, command: CreateCustomHoliday): CustomHoliday
    suspend fun deleteCustomHoliday(id: String, ownerId: UserId): Boolean

    suspend fun listEvents(calendarId: CalendarId, userId: UserId, range: InstantRange? = null): List<Event>
    suspend fun listEvents(userId: UserId, range: InstantRange): List<Event>
    suspend fun getEvent(id: EventId, userId: UserId): Event?
    suspend fun createEvent(calendarId: CalendarId, userId: UserId, command: CreateEvent): Event
    suspend fun updateEvent(
        id: EventId,
        userId: UserId,
        command: UpdateEvent,
        expectedEtag: String? = null,
    ): Event?
    suspend fun moveEvent(
        id: EventId,
        userId: UserId,
        destinationCalendarId: CalendarId,
        occurrenceStart: Instant? = null,
        expectedEtag: String? = null,
    ): List<Event>?
    suspend fun deleteEvent(id: EventId, userId: UserId, expectedEtag: String? = null): Boolean
}
