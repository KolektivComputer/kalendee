package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.sessionToken
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.events.EventAttendee
import dev.kolektiv.kalendee.events.EventAttendees
import dev.kolektiv.kalendee.events.EventInviteService
import dev.kolektiv.keel.KeelAction
import dev.kolektiv.keel.ktor.ActionRequest

class EventInviteActions(
    private val service: EventInviteService,
    private val auth: AuthService,
    private val settings: AuthSettings,
) {
    @KeelAction("kalendee.eventAttendees")
    suspend fun eventAttendees(input: EventAttendeesIn): EventAttendeesOut = mapDomainErrors("eventId") {
        val user = requireSessionUser(auth, settings)
        service.attendees(EventId.parse(input.eventId), user.id).toOut()
    }

    @KeelAction("kalendee.inviteToEvent")
    suspend fun inviteToEvent(input: InviteToEventIn): EventAttendeesOut = mapDomainErrors("username") {
        val user = requireSessionUser(auth, settings)
        service.invite(
            eventId = EventId.parse(input.eventId),
            inviterId = user.id,
            usernameOrEmail = input.username,
            name = input.name,
        ).toOut()
    }

    @KeelAction("kalendee.removeEventAttendee")
    suspend fun removeEventAttendee(input: RemoveEventAttendeeIn): EventAttendeesOut =
        mapDomainErrors("attendeeId") {
            val user = requireSessionUser(auth, settings)
            service.removeAttendee(EventId.parse(input.eventId), user.id, input.attendeeId).toOut()
        }

    @KeelAction("kalendee.respondEventInvite")
    suspend fun respondEventInvite(input: RespondEventInviteIn): RsvpOut = mapDomainErrors("status") {
        val user = requireSessionUser(auth, settings)
        RsvpOut(service.respond(EventId.parse(input.eventId), user.id, input.status).status)
    }

    @KeelAction("kalendee.setEventOpenRsvp")
    suspend fun setEventOpenRsvp(input: SetEventOpenRsvpIn): EventSummary = mapDomainErrors("eventId") {
        val user = requireSessionUser(auth, settings)
        service.setOpenRsvp(EventId.parse(input.eventId), user.id, input.enabled).toSummary()
    }

    @KeelAction("kalendee.publicRsvp")
    suspend fun publicRsvp(input: PublicRsvpIn): RsvpOut = mapDomainErrors("status") {
        val call = ActionRequest.current().call
        val viewer = call.sessionToken(settings)?.let { auth.userFor(it) }
        RsvpOut(
            service.publicRsvp(
                eventId = EventId.parse(input.eventId),
                name = input.name,
                email = input.email,
                status = input.status,
                viewerId = viewer?.id,
            ).status,
        )
    }

    @KeelAction("kalendee.rsvpByToken")
    suspend fun rsvpByToken(input: RsvpByTokenIn): RsvpOut = mapDomainErrors("token") {
        RsvpOut(service.respondByToken(input.token, input.status).status)
    }
}

fun EventAttendees.toOut(): EventAttendeesOut = EventAttendeesOut(
    attendees = attendees.map { it.toSummary() },
    openRsvp = openRsvp,
)

fun EventAttendee.toSummary(): EventAttendeeSummary = EventAttendeeSummary(
    id = id.toString(),
    userId = userId?.value,
    username = username,
    displayName = displayName,
    avatarUrl = userId?.let { attendeeId ->
        avatarVersion?.let { version -> avatarUrl(attendeeId, version) }
    },
    email = email,
    name = name,
    status = status,
)
