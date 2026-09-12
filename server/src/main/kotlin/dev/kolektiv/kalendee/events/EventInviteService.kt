package dev.kolektiv.kalendee.events

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.PublicAccessMode
import dev.kolektiv.kalendee.auth.SessionTokens
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.auth.toUuid
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.calendar.EventRsvpStatus
import dev.kolektiv.kalendee.calendar.OrganizationId
import dev.kolektiv.kalendee.db.CalendarsTable
import dev.kolektiv.kalendee.db.EventAttendeesTable
import dev.kolektiv.kalendee.db.EventsTable
import dev.kolektiv.kalendee.mail.MailService
import dev.kolektiv.kalendee.notifications.NotificationService
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

data class EventAttendee(
    val id: Uuid,
    val eventId: EventId,
    val userId: UserId?,
    val username: String?,
    val displayName: String?,
    val avatarVersion: Long?,
    val email: String?,
    val name: String?,
    val status: String,
    val invitedBy: UserId?,
    val createdAt: Instant,
    val respondedAt: Instant?,
)

data class EventAttendees(
    val attendees: List<EventAttendee>,
    val openRsvp: Boolean,
)

data class RsvpResult(
    val eventId: EventId,
    val title: String,
    val status: String,
)

data class InviteLookup(
    val eventId: EventId,
    val title: String,
    val whenText: String,
    val calendarName: String,
    val status: String?,
)

class EventInviteService(
    private val database: Database,
    private val store: CalendarStore,
    private val notifications: NotificationService,
    private val mail: MailService,
    private val auth: AuthService,
    private val clock: Clock,
) {
    suspend fun attendees(eventId: EventId, viewerId: UserId): EventAttendees {
        val event = store.getEvent(eventId, viewerId)
            ?: throw CalendarException.NotFound("event not found")
        val rows = dbQuery {
            EventAttendeesTable.selectAll()
                .where { EventAttendeesTable.eventId eq eventId.toUuid() }
                .orderBy(EventAttendeesTable.createdAt to SortOrder.ASC, EventAttendeesTable.id to SortOrder.ASC)
                .toList()
        }
        return EventAttendees(attendees = rows.map { it.toAttendee() }, openRsvp = event.openRsvp)
    }

    suspend fun inviteByToken(token: String): InviteLookup? {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return null
        val row = dbQuery {
            EventAttendeesTable.selectAll()
                .where { EventAttendeesTable.tokenHash eq SessionTokens.hash(trimmed) }
                .singleOrNull()
        } ?: return null
        val context = eventContextOrNull(EventId(row[EventAttendeesTable.eventId].toString()))
            ?: return null
        return context.toLookup(status = row[EventAttendeesTable.status])
    }

    suspend fun openRsvpInvite(eventId: EventId, viewerId: UserId? = null): InviteLookup? {
        val context = eventContextOrNull(eventId) ?: return null
        if (!context.openRsvp) return null
        // The unguessable event id is the capability: anonymous visitors only
        // get in when the event is fully public, signed-in visitors may also
        // respond on signed_in calendars.
        val access = effectiveAccess(context)
        val allowed = auth.canViewPublic(context.organizationId, viewerId) && (
            access == PublicAccessMode.PUBLIC ||
                (access == PublicAccessMode.SIGNED_IN && viewerId != null)
            )
        if (!allowed) return null
        return context.toLookup(status = null)
    }

    suspend fun invite(
        eventId: EventId,
        inviterId: UserId,
        usernameOrEmail: String,
        name: String? = null,
    ): EventAttendees {
        val inviter = auth.userById(inviterId)
            ?: throw CalendarException.NotFound("user not found")
        val event = requireWritable(eventId, inviterId)
        val query = usernameOrEmail.trim()
        if (query.isEmpty()) throw CalendarException.Invalid("username is required")
        val context = eventContext(event)
        val whenText = context.whenText()
        val target = auth.userByIdentifier(query)
        val now = clock.now()
        if (target != null) {
            if (target.id == inviterId) throw CalendarException.Invalid("cannot invite yourself")
            if (dbQuery { attendeeCountForUser(eventId, target.id) } > 0) {
                throw CalendarException.Conflict("already invited")
            }
            dbQuery {
                EventAttendeesTable.insert {
                    it[id] = Uuid.random()
                    it[EventAttendeesTable.eventId] = eventId.toUuid()
                    it[userId] = target.id.toUuid()
                    it[email] = target.email
                    it[EventAttendeesTable.name] = name?.trim()?.ifEmpty { null } ?: target.displayName
                    it[status] = EventRsvpStatus.INVITED.wire
                    it[invitedBy] = inviterId.toUuid()
                    it[tokenHash] = null
                    it[createdAt] = now
                    it[respondedAt] = null
                }
            }
            notifications.create(
                userId = target.id,
                kind = "event.invite",
                title = "${inviter.displayName} invited you to ${context.title}",
                body = whenText,
                href = context.href(),
            )
            target.email?.let { email ->
                mail.sendEventInvite(
                    to = email,
                    inviterName = inviter.displayName,
                    eventTitle = context.title,
                    whenText = whenText,
                    link = mail.absoluteLink("/"),
                )
            }
        } else {
            if (!query.contains('@')) {
                throw CalendarException.NotFound("no user matches that username or email")
            }
            val email = query.lowercase()
            if (dbQuery { attendeeCountForEmail(eventId, email) } > 0) {
                throw CalendarException.Conflict("already invited")
            }
            val token = SessionTokens.generate()
            dbQuery {
                EventAttendeesTable.insert {
                    it[id] = Uuid.random()
                    it[EventAttendeesTable.eventId] = eventId.toUuid()
                    it[userId] = null
                    it[EventAttendeesTable.email] = email
                    it[EventAttendeesTable.name] = name?.trim()?.ifEmpty { null }
                    it[status] = EventRsvpStatus.INVITED.wire
                    it[invitedBy] = inviterId.toUuid()
                    it[tokenHash] = SessionTokens.hash(token)
                    it[createdAt] = now
                    it[respondedAt] = null
                }
            }
            mail.sendEventInvite(
                to = email,
                inviterName = inviter.displayName,
                eventTitle = context.title,
                whenText = whenText,
                link = mail.absoluteLink("/rsvp/${eventId.value}?token=$token"),
            )
        }
        return attendees(eventId, inviterId)
    }

    suspend fun removeAttendee(eventId: EventId, actorId: UserId, attendeeId: String): EventAttendees {
        requireWritable(eventId, actorId)
        val parsedId = Uuid.parseOrNull(attendeeId.trim())
            ?: throw CalendarException.Invalid("invalid attendee id")
        val deleted = dbQuery {
            EventAttendeesTable.deleteWhere {
                (EventAttendeesTable.id eq parsedId) and (EventAttendeesTable.eventId eq eventId.toUuid())
            }
        }
        if (deleted == 0) throw CalendarException.NotFound("attendee not found")
        return attendees(eventId, actorId)
    }

    suspend fun respond(eventId: EventId, userId: UserId, status: String): RsvpResult {
        val parsed = EventRsvpStatus.parse(status)
        // Anyone who can see the event may respond, not just invited attendees.
        val event = store.getEvent(eventId, userId)
            ?: throw CalendarException.NotFound("event not found")
        val context = eventContext(event)
        val responder = auth.userById(userId)
        val now = clock.now()
        dbQuery {
            val row = EventAttendeesTable.selectAll()
                .where {
                    (EventAttendeesTable.eventId eq eventId.toUuid()) and
                        (EventAttendeesTable.userId eq userId.toUuid())
                }
                .singleOrNull()
            if (row != null) {
                EventAttendeesTable.update({ EventAttendeesTable.id eq row[EventAttendeesTable.id] }) {
                    it[EventAttendeesTable.status] = parsed.wire
                    it[respondedAt] = now
                }
            } else {
                EventAttendeesTable.insert {
                    it[id] = Uuid.random()
                    it[EventAttendeesTable.eventId] = eventId.toUuid()
                    it[EventAttendeesTable.userId] = userId.toUuid()
                    it[email] = responder?.email
                    it[EventAttendeesTable.name] = responder?.displayName
                    it[EventAttendeesTable.status] = parsed.wire
                    it[invitedBy] = null
                    it[tokenHash] = null
                    it[createdAt] = now
                    it[respondedAt] = now
                }
            }
        }
        notifyOwner(context, responder?.displayName ?: userId.value, parsed)
        return RsvpResult(eventId = context.eventId, title = context.title, status = parsed.wire)
    }

    suspend fun setOpenRsvp(eventId: EventId, actorId: UserId, enabled: Boolean): Event {
        requireWritable(eventId, actorId)
        dbQuery {
            EventsTable.update({ EventsTable.id eq eventId.toUuid() }) {
                it[openRsvp] = enabled
            }
        }
        return store.getEvent(eventId, actorId)
            ?: throw CalendarException.NotFound("event not found")
    }

    suspend fun publicRsvp(
        eventId: EventId,
        name: String?,
        email: String?,
        status: String,
        viewerId: UserId? = null,
    ): RsvpResult {
        val parsed = EventRsvpStatus.parse(status)
        val attendeeName = name?.trim().orEmpty()
        if (attendeeName.isEmpty()) throw CalendarException.Invalid("name is required")
        val context = eventContext(eventId)
        // The unguessable event id is the capability: anonymous visitors only
        // get in when the event is fully public, signed-in visitors may also
        // respond on signed_in calendars.
        val access = effectiveAccess(context)
        val allowed = auth.canViewPublic(context.organizationId, viewerId) && (
            access == PublicAccessMode.PUBLIC ||
                (access == PublicAccessMode.SIGNED_IN && viewerId != null)
            )
        if (!allowed) {
            throw CalendarException.NotFound("event not found")
        }
        if (!context.openRsvp) throw CalendarException.Forbidden("this event is not accepting responses")
        val normalizedEmail = email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val now = clock.now()
        dbQuery {
            val existing = normalizedEmail?.let { address ->
                EventAttendeesTable.selectAll()
                    .where {
                        (EventAttendeesTable.eventId eq eventId.toUuid()) and
                            (EventAttendeesTable.email eq address)
                    }
                    .singleOrNull()
            }
            if (existing != null) {
                EventAttendeesTable.update({ EventAttendeesTable.id eq existing[EventAttendeesTable.id] }) {
                    it[EventAttendeesTable.name] = attendeeName
                    it[EventAttendeesTable.status] = parsed.wire
                    it[respondedAt] = now
                }
            } else {
                EventAttendeesTable.insert {
                    it[id] = Uuid.random()
                    it[EventAttendeesTable.eventId] = eventId.toUuid()
                    it[userId] = null
                    it[EventAttendeesTable.email] = normalizedEmail
                    it[EventAttendeesTable.name] = attendeeName
                    it[EventAttendeesTable.status] = parsed.wire
                    it[invitedBy] = null
                    it[tokenHash] = null
                    it[createdAt] = now
                    it[respondedAt] = now
                }
            }
        }
        notifyOwner(context, attendeeName, parsed)
        return RsvpResult(eventId = context.eventId, title = context.title, status = parsed.wire)
    }

    suspend fun respondByToken(token: String, status: String): RsvpResult {
        val parsed = EventRsvpStatus.parse(status)
        val trimmed = token.trim()
        if (trimmed.isEmpty()) throw CalendarException.NotFound("invite not found")
        val row = dbQuery {
            EventAttendeesTable.selectAll()
                .where { EventAttendeesTable.tokenHash eq SessionTokens.hash(trimmed) }
                .singleOrNull()
        } ?: throw CalendarException.NotFound("invite not found")
        val context = eventContext(EventId(row[EventAttendeesTable.eventId].toString()))
        val now = clock.now()
        dbQuery {
            EventAttendeesTable.update({ EventAttendeesTable.id eq row[EventAttendeesTable.id] }) {
                it[EventAttendeesTable.status] = parsed.wire
                it[respondedAt] = now
            }
        }
        val attendeeName = row[EventAttendeesTable.name]
            ?: row[EventAttendeesTable.email]
            ?: "Someone"
        notifyOwner(context, attendeeName, parsed)
        return RsvpResult(eventId = context.eventId, title = context.title, status = parsed.wire)
    }

    private suspend fun requireWritable(eventId: EventId, actorId: UserId): Event {
        val event = store.getEvent(eventId, actorId)
            ?: throw CalendarException.NotFound("event not found")
        val calendar = store.getCalendar(event.calendarId, actorId)
            ?: throw CalendarException.NotFound("event not found")
        if (!calendar.permission.canWrite) {
            throw CalendarException.Forbidden("write access required")
        }
        return event
    }

    private fun JdbcTransaction.attendeeCountForUser(eventId: EventId, userId: UserId): Long =
        EventAttendeesTable.selectAll()
            .where {
                (EventAttendeesTable.eventId eq eventId.toUuid()) and
                    (EventAttendeesTable.userId eq userId.toUuid())
            }
            .count()

    private fun JdbcTransaction.attendeeCountForEmail(eventId: EventId, email: String): Long =
        EventAttendeesTable.selectAll()
            .where {
                (EventAttendeesTable.eventId eq eventId.toUuid()) and
                    (EventAttendeesTable.email eq email)
            }
            .count()

    private suspend fun eventContext(eventId: EventId): EventContext =
        eventContextOrNull(eventId)
            ?: throw CalendarException.NotFound("event not found")

    private suspend fun eventContextOrNull(eventId: EventId): EventContext? =
        dbQuery { eventContextRow(eventId) }

    private suspend fun eventContext(event: Event): EventContext = dbQuery {
        val row = CalendarsTable.selectAll()
            .where { CalendarsTable.id eq event.calendarId.toUuid() }
            .singleOrNull()
            ?: throw CalendarException.NotFound("calendar not found")
        EventContext(
            eventId = event.id,
            title = event.title,
            start = event.start,
            end = event.end,
            timeZone = event.timeZone,
            calendarTimeZone = row[CalendarsTable.timeZone],
            calendarName = row[CalendarsTable.displayName],
            ownerId = UserId(row[CalendarsTable.ownerId].toString()),
            openRsvp = event.openRsvp,
            accessMode = PublicAccessMode.fromWire(row[CalendarsTable.accessMode])
                ?: PublicAccessMode.INHERIT,
            organizationId = row[CalendarsTable.organizationId]?.let { OrganizationId(it.toString()) },
        )
    }

    private fun JdbcTransaction.eventContextRow(eventId: EventId): EventContext? =
        (EventsTable innerJoin CalendarsTable)
            .selectAll()
            .where { EventsTable.id eq eventId.toUuid() }
            .singleOrNull()
            ?.let { row ->
                EventContext(
                    eventId = eventId,
                    title = row[EventsTable.title],
                    start = row[EventsTable.startAt],
                    end = row[EventsTable.endAt],
                    timeZone = row[EventsTable.timeZone],
                    calendarTimeZone = row[CalendarsTable.timeZone],
                    calendarName = row[CalendarsTable.displayName],
                    ownerId = UserId(row[CalendarsTable.ownerId].toString()),
                    openRsvp = row[EventsTable.openRsvp],
                    accessMode = PublicAccessMode.fromWire(row[CalendarsTable.accessMode])
                        ?: PublicAccessMode.INHERIT,
                    organizationId = row[CalendarsTable.organizationId]?.let { OrganizationId(it.toString()) },
                )
            }

    private suspend fun effectiveAccess(context: EventContext): PublicAccessMode {
        if (context.accessMode != PublicAccessMode.INHERIT) return context.accessMode
        val userMode = auth.usersPublicAccess(context.ownerId)
        if (userMode != PublicAccessMode.INHERIT) return userMode
        return PublicAccessMode.fromWire(auth.publicAccess()) ?: PublicAccessMode.PUBLIC
    }

    private suspend fun notifyOwner(
        context: EventContext,
        attendeeName: String,
        status: EventRsvpStatus,
    ) {
        notifications.create(
            userId = context.ownerId,
            kind = "event.rsvp",
            title = "$attendeeName responded ${status.wire} to ${context.title}",
            body = "${status.wire} — ${context.whenText()}",
            href = context.href(),
        )
        auth.userById(context.ownerId)?.email?.let { email ->
            mail.sendEventRsvp(
                to = email,
                attendeeName = attendeeName,
                eventTitle = context.title,
                status = status.wire,
                link = mail.absoluteLink(context.href()),
            )
        }
    }

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }

    private suspend fun ResultRow.toAttendee(): EventAttendee {
        val attendeeId = this[EventAttendeesTable.userId]?.let { UserId(it.toString()) }
        val user = attendeeId?.let { auth.userById(it) }
        return EventAttendee(
            id = this[EventAttendeesTable.id],
            eventId = EventId(this[EventAttendeesTable.eventId].toString()),
            userId = attendeeId,
            username = user?.username,
            displayName = user?.displayName,
            avatarVersion = user?.avatarVersion,
            email = this[EventAttendeesTable.email],
            name = this[EventAttendeesTable.name],
            status = this[EventAttendeesTable.status],
            invitedBy = this[EventAttendeesTable.invitedBy]?.let { UserId(it.toString()) },
            createdAt = this[EventAttendeesTable.createdAt],
            respondedAt = this[EventAttendeesTable.respondedAt],
        )
    }
}

private data class EventContext(
    val eventId: EventId,
    val title: String,
    val start: Instant,
    val end: Instant,
    val timeZone: String?,
    val calendarTimeZone: String,
    val calendarName: String,
    val ownerId: UserId,
    val openRsvp: Boolean,
    val accessMode: PublicAccessMode,
    val organizationId: OrganizationId? = null,
) {
    fun whenText(): String {
        val zone = TimeZone.of(timeZone ?: calendarTimeZone)
        val startLocal = start.toLocalDateTime(zone)
        val endLocal = end.toLocalDateTime(zone)
        return "${startLocal.date} ${timeText(startLocal)}-${timeText(endLocal)} (${zone.id})"
    }

    fun href(): String {
        val zone = TimeZone.of(timeZone ?: calendarTimeZone)
        return "/?date=${start.toLocalDateTime(zone).date}"
    }

    fun toLookup(status: String?): InviteLookup = InviteLookup(
        eventId = eventId,
        title = title,
        whenText = whenText(),
        calendarName = calendarName,
        status = status,
    )
}

private fun timeText(local: LocalDateTime): String =
    "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"

private fun CalendarId.toUuid(): Uuid = Uuid.parse(value)
private fun EventId.toUuid(): Uuid = Uuid.parse(value)
