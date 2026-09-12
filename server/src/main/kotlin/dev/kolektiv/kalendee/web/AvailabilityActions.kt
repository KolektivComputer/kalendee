package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.PublicAccessMode
import dev.kolektiv.kalendee.auth.sessionToken
import dev.kolektiv.kalendee.availability.AvailabilityDay
import dev.kolektiv.kalendee.availability.AvailabilityService
import dev.kolektiv.kalendee.availability.AvailabilitySlot
import dev.kolektiv.kalendee.availability.AvailabilityWindow
import dev.kolektiv.kalendee.availability.CalendarAvailability
import dev.kolektiv.kalendee.availability.CalendarSlots
import dev.kolektiv.kalendee.availability.TimeSlotRequest
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.keel.KeelAction
import dev.kolektiv.keel.ktor.ActionRequest
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class AvailabilityActions(
    private val service: AvailabilityService,
    private val auth: AuthService,
    private val settings: AuthSettings,
) {
    @KeelAction("kalendee.calendarAvailability")
    suspend fun calendarAvailability(input: CalendarAvailabilityIn): CalendarAvailabilityOut =
        mapDomainErrors("calendarId") {
            val user = requireSessionUser(auth, settings)
            service.settings(CalendarId.parse(input.calendarId), user.id).toOut()
        }

    @KeelAction("kalendee.updateCalendarAvailability")
    suspend fun updateCalendarAvailability(input: UpdateCalendarAvailabilityIn): CalendarAvailabilityOut =
        mapDomainErrors("calendarId") {
            val user = requireSessionUser(auth, settings)
            service.updateSettings(
                calendarId = CalendarId.parse(input.calendarId),
                userId = user.id,
                requestsEnabled = input.requestsEnabled,
                slotMinutes = input.slotMinutes,
                accessMode = PublicAccessMode.parse(input.accessMode),
                windows = input.windows.map { AvailabilityWindow(it.weekday, it.startMinute, it.endMinute) },
            ).toOut()
        }

    @KeelAction("kalendee.calendarSlots")
    suspend fun calendarSlots(input: CalendarSlotsIn): CalendarSlotsOut = mapDomainErrors("calendarId") {
        val call = ActionRequest.current().call
        val viewer = call.sessionToken(settings)?.let { auth.userFor(it) }
        service.slots(
            calendarId = CalendarId.parse(input.calendarId),
            userId = viewer?.id,
            fromDate = parseDate(input.from, "from"),
            toDate = parseDate(input.to, "to"),
        ).toOut()
    }

    @KeelAction("kalendee.requestTimeSlot")
    suspend fun requestTimeSlot(input: RequestTimeSlotIn): RequestTimeSlotOut = mapDomainErrors("start") {
        val user = requireSessionUser(auth, settings)
        val request = service.requestSlot(
            calendarId = CalendarId.parse(input.calendarId),
            requesterId = user.id,
            start = parseInstant(input.start, "start"),
            end = parseInstant(input.end, "end"),
            message = input.message,
        )
        RequestTimeSlotOut(id = request.id.toString(), status = request.status)
    }

    @KeelAction("kalendee.publicRequestTimeSlot")
    suspend fun publicRequestTimeSlot(input: PublicRequestTimeSlotIn): RequestTimeSlotOut =
        mapDomainErrors("start") {
            val call = ActionRequest.current().call
            val viewer = call.sessionToken(settings)?.let { auth.userFor(it) }
            val request = service.publicRequestSlot(
                calendarToken = input.calendarToken,
                viewerId = viewer?.id,
                name = input.name,
                email = input.email,
                start = parseInstant(input.start, "start"),
                end = parseInstant(input.end, "end"),
                message = input.message,
            )
            RequestTimeSlotOut(id = request.id.toString(), status = request.status)
        }

    @KeelAction("kalendee.calendarRequests")
    suspend fun calendarRequests(input: CalendarRequestsIn): CalendarRequestsOut =
        mapDomainErrors("calendarId") {
            val user = requireSessionUser(auth, settings)
            CalendarRequestsOut(
                requests = service.requests(CalendarId.parse(input.calendarId), user.id)
                    .map { it.toSummary() },
            )
        }

    @KeelAction("kalendee.respondTimeSlot")
    suspend fun respondTimeSlot(input: RespondTimeSlotIn): RequestTimeSlotOut = mapDomainErrors("id") {
        val user = requireSessionUser(auth, settings)
        val request = service.respond(input.id, user.id, input.accept, input.message)
        RequestTimeSlotOut(id = request.id.toString(), status = request.status)
    }
}

fun CalendarAvailability.toOut(): CalendarAvailabilityOut = CalendarAvailabilityOut(
    calendarId = calendarId.value,
    requestsEnabled = requestsEnabled,
    slotMinutes = slotMinutes,
    accessMode = accessMode.wire,
    effectiveAccessMode = effectiveAccessMode.wire,
    timeZone = timeZone,
    windows = windows.map { AvailabilityWindowIn(it.weekday, it.startMinute, it.endMinute) },
)

fun CalendarSlots.toOut(): CalendarSlotsOut = CalendarSlotsOut(
    calendarId = calendarId.value,
    timeZone = timeZone,
    requestsEnabled = requestsEnabled,
    days = days.map { it.toOut() },
)

fun AvailabilityDay.toOut(): AvailabilityDayOut = AvailabilityDayOut(
    date = date.toString(),
    slots = slots.map { it.toOut() },
)

fun AvailabilitySlot.toOut(): AvailabilitySlotOut = AvailabilitySlotOut(
    start = start.toString(),
    end = end.toString(),
    available = available,
)

fun TimeSlotRequest.toSummary(): TimeSlotRequestSummary = TimeSlotRequestSummary(
    id = id.toString(),
    calendarId = calendarId.value,
    calendarName = calendarName,
    requesterUserId = requesterId?.value,
    requesterName = requesterName,
    requesterUsername = requesterUsername,
    requesterAvatarUrl = requesterId?.let { requesterAvatarVersion?.let { version -> avatarUrl(it, version) } },
    requesterEmail = requesterEmail,
    start = start.toString(),
    end = end.toString(),
    message = message,
    status = status,
    createdAt = createdAt.toString(),
)

private fun parseDate(raw: String, field: String): LocalDate = try {
    LocalDate.parse(raw.trim())
} catch (_: IllegalArgumentException) {
    throw CalendarException.Invalid("invalid $field date: $raw")
}

private fun parseInstant(raw: String, field: String): Instant = try {
    Instant.parse(raw.trim())
} catch (_: IllegalArgumentException) {
    throw CalendarException.Invalid("invalid $field instant: $raw")
}
