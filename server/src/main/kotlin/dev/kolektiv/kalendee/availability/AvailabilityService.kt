package dev.kolektiv.kalendee.availability

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.PublicAccessMode
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.auth.toUuid
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.InstantRange
import dev.kolektiv.kalendee.calendar.OrganizationId
import dev.kolektiv.kalendee.db.CalendarAvailabilityTable
import dev.kolektiv.kalendee.db.CalendarsTable
import dev.kolektiv.kalendee.db.TimeSlotRequestsTable
import dev.kolektiv.kalendee.mail.MailService
import dev.kolektiv.kalendee.notifications.NotificationService
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

data class AvailabilityWindow(
    val weekday: Int,
    val startMinute: Int,
    val endMinute: Int,
)

data class CalendarAvailability(
    val calendarId: CalendarId,
    val requestsEnabled: Boolean,
    val slotMinutes: Int,
    val accessMode: PublicAccessMode,
    val effectiveAccessMode: PublicAccessMode,
    val timeZone: String,
    val windows: List<AvailabilityWindow>,
)

data class AvailabilitySlot(
    val start: Instant,
    val end: Instant,
    val available: Boolean,
)

data class AvailabilityDay(
    val date: LocalDate,
    val slots: List<AvailabilitySlot>,
)

data class CalendarSlots(
    val calendarId: CalendarId,
    val timeZone: String,
    val requestsEnabled: Boolean,
    val days: List<AvailabilityDay>,
)

data class TimeSlotRequest(
    val id: Uuid,
    val calendarId: CalendarId,
    val calendarName: String,
    val requesterId: UserId?,
    val requesterName: String?,
    val requesterUsername: String?,
    val requesterAvatarVersion: Long?,
    val requesterEmail: String?,
    val start: Instant,
    val end: Instant,
    val message: String?,
    val status: String,
    val createdAt: Instant,
    val respondedAt: Instant?,
    val respondedBy: UserId?,
) {
    companion object {
        const val PENDING = "pending"
        const val ACCEPTED = "accepted"
        const val DECLINED = "declined"
    }
}

class AvailabilityService(
    private val database: Database,
    private val store: CalendarStore,
    private val auth: AuthService,
    private val clock: Clock,
    private val notifications: NotificationService,
    private val mail: MailService,
) {
    suspend fun settings(calendarId: CalendarId, userId: UserId): CalendarAvailability {
        val calendar = requireOwner(calendarId, userId)
        return availabilityOf(calendar)
    }

    suspend fun updateSettings(
        calendarId: CalendarId,
        userId: UserId,
        requestsEnabled: Boolean,
        slotMinutes: Int,
        accessMode: PublicAccessMode,
        windows: List<AvailabilityWindow>,
    ): CalendarAvailability {
        requireOwner(calendarId, userId)
        if (slotMinutes !in MinSlotMinutes..MaxSlotMinutes) {
            throw CalendarException.Invalid("slotMinutes must be between $MinSlotMinutes and $MaxSlotMinutes")
        }
        validateWindows(windows)
        val now = clock.now()
        dbQuery {
            CalendarsTable.update({ CalendarsTable.id eq calendarId.toUuid() }) {
                it[CalendarsTable.requestsEnabled] = requestsEnabled
                it[CalendarsTable.slotMinutes] = slotMinutes
                it[CalendarsTable.accessMode] = accessMode.wire
                it[updatedAt] = now
            }
            CalendarAvailabilityTable.deleteWhere {
                CalendarAvailabilityTable.calendarId eq calendarId.toUuid()
            }
            windows.forEach { window ->
                CalendarAvailabilityTable.insert {
                    it[id] = Uuid.random()
                    it[CalendarAvailabilityTable.calendarId] = calendarId.toUuid()
                    it[weekday] = window.weekday
                    it[startMinute] = window.startMinute
                    it[endMinute] = window.endMinute
                    it[createdAt] = now
                }
            }
        }
        val updated = store.getCalendar(calendarId, userId)
            ?: throw CalendarException.NotFound("calendar not found")
        return availabilityOf(updated)
    }

    suspend fun slots(
        calendarId: CalendarId,
        userId: UserId?,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): CalendarSlots {
        val cappedTo = cappedRange(fromDate, toDate)
        val calendar = if (userId != null) {
            store.getCalendar(calendarId, userId)
                ?: publicCalendar(calendarId)?.takeIf { auth.canViewPublic(it, userId) }
                ?: throw CalendarException.NotFound("calendar not found")
        } else {
            val publicCalendar = publicCalendar(calendarId)
                ?: throw CalendarException.NotFound("calendar not found")
            if (auth.effectivePublicAccess(publicCalendar) != PublicAccessMode.PUBLIC) {
                throw CalendarException.NotFound("calendar not found")
            }
            publicCalendar
        }
        return computeSlots(calendar, fromDate, cappedTo)
    }

    suspend fun publicSlots(
        token: String,
        viewerId: UserId?,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): CalendarSlots {
        val cappedTo = cappedRange(fromDate, toDate)
        val calendar = store.publicCalendar(token.trim())
            ?: throw CalendarException.NotFound("calendar not found")
        if (!auth.canViewPublic(calendar, viewerId)) {
            throw CalendarException.NotFound("calendar not found")
        }
        if (viewerId == null && auth.effectivePublicAccess(calendar) != PublicAccessMode.PUBLIC) {
            throw CalendarException.NotFound("calendar not found")
        }
        return computeSlots(calendar, fromDate, cappedTo)
    }

    suspend fun requestSlot(
        calendarId: CalendarId,
        requesterId: UserId,
        start: Instant,
        end: Instant,
        message: String?,
    ): TimeSlotRequest {
        val calendar = store.getCalendar(calendarId, requesterId)
            ?: throw CalendarException.NotFound("calendar not found")
        if (calendar.permission != CalendarPermission.READ && calendar.permission != CalendarPermission.FOLLOW) {
            throw CalendarException.Forbidden("only readers and followers may request a time")
        }
        if (!calendar.requestsEnabled) {
            throw CalendarException.Invalid("this calendar is not accepting requests")
        }
        val requester = auth.userById(requesterId)
            ?: throw CalendarException.NotFound("user not found")
        val now = clock.now()
        val zone = validateSlot(calendar, start, end, now)
        if (hasOverlappingPendingRequest(calendar.id, start, end, requesterId = requesterId)) {
            throw CalendarException.Conflict("a pending request already covers this time")
        }
        return insertSlotRequest(
            calendar = calendar,
            requesterId = requester.id,
            requesterName = requester.displayName,
            requesterUsername = requester.username,
            requesterAvatarVersion = requester.avatarVersion,
            requesterEmail = requester.email,
            start = start,
            end = end,
            message = message,
            zone = zone,
        )
    }

    suspend fun publicRequestSlot(
        calendarToken: String,
        viewerId: UserId?,
        name: String,
        email: String?,
        start: Instant,
        end: Instant,
        message: String?,
    ): TimeSlotRequest {
        val calendar = store.publicCalendar(calendarToken.trim())
            ?: throw CalendarException.NotFound("calendar not found")
        if (!calendar.requestsEnabled) {
            throw CalendarException.Invalid("this calendar is not accepting meeting requests")
        }
        // The unguessable public link token is the capability: anonymous visitors
        // only get in when the calendar is fully public, signed-in visitors may
        // request on signed_in calendars too. Private organizations additionally
        // require the viewer to be a member.
        if (!auth.canViewPublic(calendar, viewerId)) {
            throw CalendarException.NotFound("calendar not found")
        }
        if (viewerId == null && auth.effectivePublicAccess(calendar) != PublicAccessMode.PUBLIC) {
            throw CalendarException.NotFound("calendar not found")
        }
        val requesterName = name.trim()
        if (requesterName.isEmpty()) throw CalendarException.Invalid("name is required")
        val normalizedEmail = email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val now = clock.now()
        val zone = validateSlot(calendar, start, end, now)
        // Dedupe anonymous requests by email; visitors without an email can
        // request the same window again (they cannot be told apart).
        if (normalizedEmail != null &&
            hasOverlappingPendingRequest(calendar.id, start, end, requesterEmail = normalizedEmail)
        ) {
            throw CalendarException.Conflict("a pending request already covers this time")
        }
        val viewer = viewerId?.let { auth.userById(it) }
        return insertSlotRequest(
            calendar = calendar,
            requesterId = viewerId,
            requesterName = requesterName,
            requesterUsername = viewer?.username,
            requesterAvatarVersion = viewer?.avatarVersion,
            requesterEmail = normalizedEmail,
            start = start,
            end = end,
            message = message,
            zone = zone,
        )
    }

    suspend fun requests(calendarId: CalendarId, ownerId: UserId): List<TimeSlotRequest> {
        val calendar = requireOwner(calendarId, ownerId)
        val rows = dbQuery {
            TimeSlotRequestsTable.selectAll()
                .where { TimeSlotRequestsTable.calendarId eq calendar.id.toUuid() }
                .orderBy(
                    TimeSlotRequestsTable.createdAt to SortOrder.DESC,
                    TimeSlotRequestsTable.id to SortOrder.DESC,
                )
                .map { it.toRequestRow() }
        }
        return rows.map { row ->
            val requester = row.requesterId?.let { auth.userById(it) }
            TimeSlotRequest(
                id = row.id,
                calendarId = calendar.id,
                calendarName = calendar.displayName,
                requesterId = row.requesterId,
                requesterName = row.requesterName ?: requester?.displayName,
                requesterUsername = requester?.username,
                requesterAvatarVersion = requester?.avatarVersion,
                requesterEmail = row.requesterEmail ?: requester?.email,
                start = row.start,
                end = row.end,
                message = row.message,
                status = row.status,
                createdAt = row.createdAt,
                respondedAt = row.respondedAt,
                respondedBy = row.respondedBy,
            )
        }
    }

    suspend fun respond(
        requestId: String,
        ownerId: UserId,
        accept: Boolean,
        message: String?,
    ): TimeSlotRequest {
        val id = Uuid.parseOrNull(requestId.trim())
            ?: throw CalendarException.Invalid("invalid request id")
        val row = dbQuery {
            TimeSlotRequestsTable.selectAll()
                .where { TimeSlotRequestsTable.id eq id }
                .singleOrNull()
                ?.toRequestRow()
        } ?: throw CalendarException.NotFound("request not found")
        val calendar = store.getCalendar(row.calendarId, ownerId)
            ?: throw CalendarException.NotFound("calendar not found")
        if (calendar.permission != CalendarPermission.OWNER) {
            throw CalendarException.Forbidden("owner only")
        }
        if (row.status != TimeSlotRequest.PENDING) {
            throw CalendarException.Conflict("request already handled")
        }
        val requester = row.requesterId?.let { auth.userById(it) }
        val requesterName = row.requesterName ?: requester?.displayName ?: "Someone"
        val now = clock.now()
        val nextStatus = if (accept) TimeSlotRequest.ACCEPTED else TimeSlotRequest.DECLINED
        if (accept) {
            store.createEvent(
                calendar.id,
                ownerId,
                CreateEvent(
                    title = "Meeting with $requesterName",
                    description = row.message,
                    start = row.start,
                    end = row.end,
                    timeZone = calendar.timeZone,
                ),
            )
        }
        val updated = dbQuery {
            TimeSlotRequestsTable.update({
                (TimeSlotRequestsTable.id eq id) and
                    (TimeSlotRequestsTable.status eq TimeSlotRequest.PENDING)
            }) {
                it[status] = nextStatus
                it[respondedAt] = now
                it[respondedBy] = ownerId.toUuid()
            }
        }
        if (updated == 0) {
            throw CalendarException.Conflict("request already handled")
        }
        val zone = TimeZone.of(calendar.timeZone)
        val whenText = whenText(zone, row.start, row.end)
        val decision = if (accept) "accepted" else "declined"
        row.requesterId?.let { requesterId ->
            notifications.create(
                userId = requesterId,
                kind = if (accept) "slot.accepted" else "slot.declined",
                title = "Your time request on ${calendar.displayName} was $decision",
                body = whenText,
                href = "/",
            )
        }
        val decisionEmail = row.requesterEmail ?: requester?.email
        decisionEmail?.let { email ->
            mail.sendTimeSlotDecision(
                to = email,
                calendarName = calendar.displayName,
                whenText = whenText,
                accepted = accept,
            )
        }
        return TimeSlotRequest(
            id = id,
            calendarId = calendar.id,
            calendarName = calendar.displayName,
            requesterId = row.requesterId,
            requesterName = row.requesterName ?: requester?.displayName,
            requesterUsername = requester?.username,
            requesterAvatarVersion = requester?.avatarVersion,
            requesterEmail = row.requesterEmail ?: requester?.email,
            start = row.start,
            end = row.end,
            message = row.message,
            status = nextStatus,
            createdAt = row.createdAt,
            respondedAt = now,
            respondedBy = ownerId,
        )
    }

    private suspend fun requireOwner(calendarId: CalendarId, userId: UserId): Calendar {
        val calendar = store.getCalendar(calendarId, userId)
            ?: throw CalendarException.NotFound("calendar not found")
        if (calendar.permission != CalendarPermission.OWNER) {
            throw CalendarException.Forbidden("owner only")
        }
        return calendar
    }

    private suspend fun validateSlot(
        calendar: Calendar,
        start: Instant,
        end: Instant,
        now: Instant,
    ): TimeZone {
        if (end <= start) throw CalendarException.Invalid("end must be after start")
        if (start <= now) throw CalendarException.Invalid("start must be in the future")
        val duration = end - start
        if (duration < calendar.slotMinutes.minutes) {
            throw CalendarException.Invalid("slot must be at least ${calendar.slotMinutes} minutes")
        }
        if (duration > MaxRequestDuration) {
            throw CalendarException.Invalid("slot must be at most 8 hours")
        }
        val zone = TimeZone.of(calendar.timeZone)
        val localDate = start.toLocalDateTime(zone).date
        val insideOfficeHours = windows(calendar.id)
            .filter { it.weekday == localDate.dayOfWeek.isoDayNumber - 1 }
            .any { window ->
                val windowStart = atMinute(localDate, window.startMinute, zone)
                val windowEnd = atMinute(localDate, window.endMinute, zone)
                start >= windowStart && end <= windowEnd
            }
        if (!insideOfficeHours) {
            throw CalendarException.Invalid("slot is outside office hours")
        }
        val busy = busyIntervals(calendar, InstantRange(start, end))
        if (busy.any { it.overlaps(start, end) }) {
            throw CalendarException.Invalid("slot is not available")
        }
        return zone
    }

    private suspend fun hasOverlappingPendingRequest(
        calendarId: CalendarId,
        start: Instant,
        end: Instant,
        requesterId: UserId? = null,
        requesterEmail: String? = null,
    ): Boolean {
        val requesterMatch = when {
            requesterId != null -> TimeSlotRequestsTable.requesterId eq requesterId.toUuid()
            requesterEmail != null -> TimeSlotRequestsTable.requesterEmail eq requesterEmail
            else -> return false
        }
        return dbQuery {
            TimeSlotRequestsTable.selectAll()
                .where {
                    (TimeSlotRequestsTable.calendarId eq calendarId.toUuid()) and
                        (TimeSlotRequestsTable.status eq TimeSlotRequest.PENDING) and
                        (TimeSlotRequestsTable.startAt less end) and
                        (TimeSlotRequestsTable.endAt greater start) and
                        requesterMatch
                }
                .count() > 0
        }
    }

    private suspend fun insertSlotRequest(
        calendar: Calendar,
        requesterId: UserId?,
        requesterName: String,
        requesterUsername: String?,
        requesterAvatarVersion: Long?,
        requesterEmail: String?,
        start: Instant,
        end: Instant,
        message: String?,
        zone: TimeZone,
    ): TimeSlotRequest {
        val now = clock.now()
        val trimmedMessage = message?.trim()?.takeIf { it.isNotEmpty() }
        val id = Uuid.random()
        dbQuery {
            TimeSlotRequestsTable.insert {
                it[TimeSlotRequestsTable.id] = id
                it[TimeSlotRequestsTable.calendarId] = calendar.id.toUuid()
                it[TimeSlotRequestsTable.requesterId] = requesterId?.toUuid()
                it[TimeSlotRequestsTable.requesterName] = requesterName
                it[TimeSlotRequestsTable.requesterEmail] = requesterEmail
                it[startAt] = start
                it[endAt] = end
                it[TimeSlotRequestsTable.message] = trimmedMessage
                it[status] = TimeSlotRequest.PENDING
                it[createdAt] = now
                it[respondedAt] = null
                it[respondedBy] = null
            }
        }
        val whenText = whenText(zone, start, end)
        notifications.create(
            userId = calendar.ownerId,
            kind = "slot.request",
            title = "$requesterName requested a time on ${calendar.displayName}",
            body = whenText + (trimmedMessage?.let { "\n$it" } ?: ""),
            href = "/settings?tab=availability",
        )
        auth.userById(calendar.ownerId)?.email?.let { ownerEmail ->
            mail.sendTimeSlotRequest(
                to = ownerEmail,
                requesterName = requesterName,
                calendarName = calendar.displayName,
                whenText = whenText,
                message = trimmedMessage,
            )
        }
        return TimeSlotRequest(
            id = id,
            calendarId = calendar.id,
            calendarName = calendar.displayName,
            requesterId = requesterId,
            requesterName = requesterName,
            requesterUsername = requesterUsername,
            requesterAvatarVersion = requesterAvatarVersion,
            requesterEmail = requesterEmail,
            start = start,
            end = end,
            message = trimmedMessage,
            status = TimeSlotRequest.PENDING,
            createdAt = now,
            respondedAt = null,
            respondedBy = null,
        )
    }

    private suspend fun availabilityOf(calendar: Calendar): CalendarAvailability = CalendarAvailability(
        calendarId = calendar.id,
        requestsEnabled = calendar.requestsEnabled,
        slotMinutes = calendar.slotMinutes,
        accessMode = calendar.accessMode,
        effectiveAccessMode = auth.effectivePublicAccess(calendar),
        timeZone = calendar.timeZone,
        windows = windows(calendar.id),
    )

    private suspend fun windows(calendarId: CalendarId): List<AvailabilityWindow> = dbQuery {
        CalendarAvailabilityTable.selectAll()
            .where { CalendarAvailabilityTable.calendarId eq calendarId.toUuid() }
            .orderBy(
                CalendarAvailabilityTable.weekday to SortOrder.ASC,
                CalendarAvailabilityTable.startMinute to SortOrder.ASC,
            )
            .map { row ->
                AvailabilityWindow(
                    weekday = row[CalendarAvailabilityTable.weekday],
                    startMinute = row[CalendarAvailabilityTable.startMinute],
                    endMinute = row[CalendarAvailabilityTable.endMinute],
                )
            }
    }

    private suspend fun computeSlots(
        calendar: Calendar,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): CalendarSlots {
        val zone = TimeZone.of(calendar.timeZone)
        val range = InstantRange(
            start = fromDate.atStartOfDayIn(zone),
            end = toDate.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone),
        )
        val busy = busyIntervals(calendar, range)
        return CalendarSlots(
            calendarId = calendar.id,
            timeZone = calendar.timeZone,
            requestsEnabled = calendar.requestsEnabled,
            days = buildDays(fromDate, toDate, zone, windows(calendar.id), busy, calendar.slotMinutes),
        )
    }

    private suspend fun busyIntervals(calendar: Calendar, range: InstantRange): List<Interval> {
        val ownerCalendars = store.listCalendars(calendar.ownerId)
            .filter { it.permission == CalendarPermission.OWNER }
        val events = mutableListOf<Event>()
        ownerCalendars.forEach { owned -> events += store.listEvents(owned.id, calendar.ownerId, range) }
        if (ownerCalendars.none { it.id == calendar.id }) {
            events += store.listPublicEvents(calendar.id, range)
        }
        return events.flatMap { it.busyIntervals(calendar.timeZone) }.merged()
    }

    private fun buildDays(
        fromDate: LocalDate,
        toDate: LocalDate,
        zone: TimeZone,
        windows: List<AvailabilityWindow>,
        busy: List<Interval>,
        slotMinutes: Int,
    ): List<AvailabilityDay> {
        val byWeekday = windows.groupBy { it.weekday }
        val days = mutableListOf<AvailabilityDay>()
        var date = fromDate
        while (date <= toDate) {
            val slots = mutableListOf<AvailabilitySlot>()
            val weekday = date.dayOfWeek.isoDayNumber - 1
            byWeekday[weekday].orEmpty().forEach { window ->
                var slotStart = atMinute(date, window.startMinute, zone)
                val windowEnd = atMinute(date, window.endMinute, zone)
                while (slotStart + slotMinutes.minutes <= windowEnd) {
                    val slotEnd = slotStart + slotMinutes.minutes
                    slots += AvailabilitySlot(
                        start = slotStart,
                        end = slotEnd,
                        available = busy.none { it.overlaps(slotStart, slotEnd) },
                    )
                    slotStart = slotEnd
                }
            }
            days += AvailabilityDay(date = date, slots = slots.sortedBy { it.start })
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return days
    }

    private suspend fun publicCalendar(calendarId: CalendarId): Calendar? = dbQuery {
        CalendarsTable.selectAll()
            .where {
                (CalendarsTable.id eq calendarId.toUuid()) and
                    (CalendarsTable.publicLinkEnabled eq true)
            }
            .singleOrNull()
            ?.let { row ->
                Calendar(
                    id = CalendarId(row[CalendarsTable.id].toString()),
                    ownerId = UserId(row[CalendarsTable.ownerId].toString()),
                    displayName = row[CalendarsTable.displayName],
                    description = row[CalendarsTable.description],
                    timeZone = row[CalendarsTable.timeZone],
                    color = row[CalendarsTable.color],
                    permission = CalendarPermission.READ,
                    publicLinkEnabled = true,
                    publicLinkToken = row[CalendarsTable.publicLinkToken],
                    requestsEnabled = row[CalendarsTable.requestsEnabled],
                    slotMinutes = row[CalendarsTable.slotMinutes],
                    accessMode = PublicAccessMode.fromWire(row[CalendarsTable.accessMode])
                        ?: PublicAccessMode.INHERIT,
                    createdAt = row[CalendarsTable.createdAt],
                    updatedAt = row[CalendarsTable.updatedAt],
                    organizationId = row[CalendarsTable.organizationId]?.let { OrganizationId(it.toString()) },
                )
            }
    }

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }
}

private data class RequestRow(
    val id: Uuid,
    val calendarId: CalendarId,
    val requesterId: UserId?,
    val requesterName: String?,
    val requesterEmail: String?,
    val start: Instant,
    val end: Instant,
    val message: String?,
    val status: String,
    val createdAt: Instant,
    val respondedAt: Instant?,
    val respondedBy: UserId?,
)

private data class Interval(val start: Instant, val end: Instant) {
    fun overlaps(otherStart: Instant, otherEnd: Instant): Boolean =
        otherStart < end && otherEnd > start
}

private fun ResultRow.toRequestRow(): RequestRow = RequestRow(
    id = this[TimeSlotRequestsTable.id],
    calendarId = CalendarId(this[TimeSlotRequestsTable.calendarId].toString()),
    requesterId = this[TimeSlotRequestsTable.requesterId]?.let { UserId(it.toString()) },
    requesterName = this[TimeSlotRequestsTable.requesterName],
    requesterEmail = this[TimeSlotRequestsTable.requesterEmail],
    start = this[TimeSlotRequestsTable.startAt],
    end = this[TimeSlotRequestsTable.endAt],
    message = this[TimeSlotRequestsTable.message],
    status = this[TimeSlotRequestsTable.status],
    createdAt = this[TimeSlotRequestsTable.createdAt],
    respondedAt = this[TimeSlotRequestsTable.respondedAt],
    respondedBy = this[TimeSlotRequestsTable.respondedBy]?.let { UserId(it.toString()) },
)

private fun Event.busyIntervals(zoneId: String): List<Interval> {
    if (!allDay) return listOf(Interval(start, end))
    val zone = TimeZone.of(zoneId)
    var date = start.toLocalDateTime(zone).date
    val lastDate = end.toLocalDateTime(zone).date
    val intervals = mutableListOf<Interval>()
    while (date < lastDate) {
        val dayStart = date.atStartOfDayIn(zone)
        intervals += Interval(dayStart, date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone))
        date = date.plus(1, DateTimeUnit.DAY)
    }
    return intervals
}

private fun List<Interval>.merged(): List<Interval> {
    if (isEmpty()) return emptyList()
    val merged = mutableListOf<Interval>()
    sortedBy { it.start }.forEach { interval ->
        val last = merged.lastOrNull()
        if (last != null && interval.start <= last.end) {
            merged[merged.size - 1] = Interval(last.start, maxOf(last.end, interval.end))
        } else {
            merged += interval
        }
    }
    return merged
}

private fun validateWindows(windows: List<AvailabilityWindow>) {
    if (windows.size > MaxWindows) {
        throw CalendarException.Invalid("windows must contain at most $MaxWindows entries")
    }
    windows.forEach { window ->
        if (window.weekday !in 0..6) {
            throw CalendarException.Invalid("weekday must be between 0 and 6")
        }
        if (window.startMinute < 0 || window.endMinute > MinutesPerDay ||
            window.startMinute >= window.endMinute
        ) {
            throw CalendarException.Invalid("window must start before it ends within the day")
        }
    }
    windows.groupBy { it.weekday }.forEach { (_, dayWindows) ->
        dayWindows.sortedBy { it.startMinute }.zipWithNext().forEach { (first, second) ->
            if (second.startMinute < first.endMinute) {
                throw CalendarException.Invalid("windows must not overlap")
            }
        }
    }
}

private fun cappedRange(fromDate: LocalDate, toDate: LocalDate): LocalDate {
    if (toDate < fromDate) {
        throw CalendarException.Invalid("to must be on or after from")
    }
    return minOf(toDate, fromDate.plus(MaxRangeDays - 1, DateTimeUnit.DAY))
}

private fun atMinute(date: LocalDate, minute: Int, zone: TimeZone): Instant {
    if (minute >= MinutesPerDay) {
        return date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
    }
    return LocalDateTime(date.year, date.month, date.day, minute / 60, minute % 60).toInstant(zone)
}

private fun whenText(zone: TimeZone, start: Instant, end: Instant): String {
    val startLocal = start.toLocalDateTime(zone)
    val endLocal = end.toLocalDateTime(zone)
    return "${startLocal.date} ${timeText(startLocal)}-${timeText(endLocal)} (${zone.id})"
}

private fun timeText(local: LocalDateTime): String =
    "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"

private fun CalendarId.toUuid(): Uuid = Uuid.parse(value)

private const val MinSlotMinutes = 15
private const val MaxSlotMinutes = 240
private const val MinutesPerDay = 24 * 60
private const val MaxWindows = 50
private const val MaxRangeDays = 62
private val MaxRequestDuration = 8.hours
