package dev.kolektiv.kalendee.demo

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.PasswordHasher
import dev.kolektiv.kalendee.auth.PublicAccessMode
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.auth.toUser
import dev.kolektiv.kalendee.availability.AvailabilityService
import dev.kolektiv.kalendee.availability.AvailabilityWindow
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.calendar.EventRsvpStatus
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.calendar.Recurrence
import dev.kolektiv.kalendee.calendar.RecurrenceFrequency
import dev.kolektiv.kalendee.config.AppSettings
import dev.kolektiv.kalendee.db.EventAttendeesTable
import dev.kolektiv.kalendee.db.UsersTable
import dev.kolektiv.kalendee.events.EventInviteService
import dev.kolektiv.kalendee.friends.FriendshipService
import dev.kolektiv.kalendee.friends.RelationshipFriends
import dev.kolektiv.kalendee.friends.RelationshipNone
import dev.kolektiv.kalendee.friends.RelationshipPendingIn
import dev.kolektiv.kalendee.friends.RelationshipPendingOut
import dev.kolektiv.kalendee.groups.GroupService
import dev.kolektiv.kalendee.notifications.NotificationService
import dev.kolektiv.kalendee.reminders.ReminderService
import dev.kolektiv.kalendee.storage.AvatarStorage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.time.Clock
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
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory

class DemoSeeder(
    private val database: Database,
    private val hasher: PasswordHasher,
    private val auth: AuthService,
    private val groups: GroupService,
    private val store: CalendarStore,
    private val invites: EventInviteService,
    private val reminders: ReminderService,
    private val notifications: NotificationService,
    private val friendships: FriendshipService,
    private val availability: AvailabilityService,
    private val storage: AvatarStorage,
    private val settings: AppSettings,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(DemoSeeder::class.java)

    suspend fun seed(): Boolean {
        val zone = demoZone()
        val now = clock.now()
        val today = now.toLocalDateTime(zone).date
        val weekStart = today.minus(today.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

        val demo = ensureUser("demo", "Demo User", "demo@example.com", "accent", zone)
        val sam = ensureUser("sam", "Sam Rivera", "sam@example.com", "info", zone)
        val alex = ensureUser("alex", "Alex Kim", "alex@example.com", "success", zone)

        ensureAvatar(demo, 0x6366F1, 0x8B5CF6)
        ensureAvatar(sam, 0x0EA5E9, 0x14B8A6)
        ensureAvatar(alex, 0xF59E0B, 0xEF4444)

        val owned = store.listCalendars(demo.id)
            .filter { it.ownerId == demo.id }
            .associateBy { it.displayName }
        if (alreadySeeded(demo.id, owned)) {
            log.info("demo data already present; skipping demo seed")
            return false
        }

        val calendars = demoCalendars(demo, zone, owned)
        val work = calendars.getValue("Work")
        val personal = calendars.getValue("Personal")
        val family = calendars.getValue("Family")
        val birthdays = calendars.getValue("Birthdays")

        seedDemoEvents(demo, zone, today, weekStart, work, personal, family, birthdays)

        val community = ensureCalendar(
            owner = sam,
            zone = zone,
            name = "Community events",
            color = "info",
            description = "Local happenings around town.",
        )
        seedCommunityEvents(sam, zone, weekStart, community)

        store.addShare(work.id, demo.id, sam.id, CalendarPermission.READ)
        followCommunity(demo, sam, community)
        seedInvites(demo, sam, work, personal, zone)
        seedFriendships(demo, sam, alex)
        seedReminders(demo, work)
        seedHolidays(demo)
        seedAvailability(demo, work)

        val summary = calendars.entries
            .map { (name, calendar) -> "$name=${store.listEvents(calendar.id, demo.id).size}" }
            .joinToString(", ")
        log.info("demo data seeded for '{}': {}", demo.username, summary)
        return true
    }

    private suspend fun demoCalendars(
        demo: User,
        zone: TimeZone,
        existing: Map<String, Calendar>,
    ): Map<String, Calendar> = DemoCalendarSpecs.associate { spec ->
        val existingCalendar = existing[spec.name]
        if (existingCalendar != null) {
            spec.name to existingCalendar
        } else {
            spec.name to store.createCalendar(
                demo.id,
                CreateCalendar(
                    displayName = spec.name,
                    description = spec.description,
                    timeZone = zone.id,
                    color = spec.color,
                ),
            )
        }
    }

    private suspend fun ensureCalendar(
        owner: User,
        zone: TimeZone,
        name: String,
        color: String,
        description: String,
    ): Calendar = store.listCalendars(owner.id)
        .firstOrNull { it.ownerId == owner.id && it.displayName == name }
        ?: store.createCalendar(
            owner.id,
            CreateCalendar(
                displayName = name,
                description = description,
                timeZone = zone.id,
                color = color,
            ),
        )

    private suspend fun alreadySeeded(demoId: UserId, calendars: Map<String, Calendar>): Boolean {
        for ((name, titles) in RequiredEventTitles) {
            val calendar = calendars[name] ?: return false
            val present = store.listEvents(calendar.id, demoId).map { it.title }.toSet()
            if (!present.containsAll(titles)) return false
        }
        return true
    }

    private suspend fun seedDemoEvents(
        demo: User,
        zone: TimeZone,
        today: LocalDate,
        weekStart: LocalDate,
        work: Calendar,
        personal: Calendar,
        family: Calendar,
        birthdays: Calendar,
    ) {
        val monday = weekStart
        val wednesday = weekStart.plus(2, DateTimeUnit.DAY)
        val thursday = weekStart.plus(3, DateTimeUnit.DAY)
        val friday = weekStart.plus(4, DateTimeUnit.DAY)
        val saturday = weekStart.plus(5, DateTimeUnit.DAY)
        val sunday = weekStart.plus(6, DateTimeUnit.DAY)
        val nextMonday = weekStart.plus(7, DateTimeUnit.DAY)
        val nextTuesday = weekStart.plus(8, DateTimeUnit.DAY)
        val lastWednesday = weekStart.minus(5, DateTimeUnit.DAY)

        val workKnown = knownEvents(work, demo.id)
        seedEvent(
            work,
            demo.id,
            zone,
            workKnown,
            "Team standup",
            at(monday, 9, 15, zone),
            at(monday, 9, 30, zone),
            location = "Video call",
            url = "https://meet.example.com/standup",
            recurrence = Recurrence(RecurrenceFrequency.DAILY, count = 5),
        )
        seedEvent(
            work,
            demo.id,
            zone,
            workKnown,
            "Focus time",
            at(today, 9, 30, zone),
            at(today, 11, 30, zone),
            description = "Deep work block — notifications off.",
            location = "Home office",
        )
        seedEvent(
            work,
            demo.id,
            zone,
            workKnown,
            "Design review",
            at(wednesday, 14, 0, zone),
            at(wednesday, 15, 0, zone),
            description = "Review the week grid, event dialog, and all-day row polish.",
            location = "Video call",
            url = "https://meet.example.com/design-review",
            status = EventStatus.TENTATIVE,
        )
        seedEvent(
            work,
            demo.id,
            zone,
            workKnown,
            "Roadmap planning",
            at(wednesday, 15, 0, zone),
            at(wednesday, 16, 0, zone),
            description = "Prioritise the next milestone and cut scope.",
            url = "https://meet.example.com/roadmap",
        )
        seedEvent(
            work,
            demo.id,
            zone,
            workKnown,
            "Interview: backend candidate",
            at(wednesday, 15, 30, zone),
            at(wednesday, 16, 30, zone),
            description = "Kotlin/Ktor pairing exercise with the platform team.",
            location = "Room 3",
            url = "https://meet.example.com/interview",
        )
        seedEvent(
            work,
            demo.id,
            zone,
            workKnown,
            "1:1 with Sam",
            at(thursday, 11, 0, zone),
            at(thursday, 11, 30, zone),
            location = "Coffee corner",
        )
        seedEvent(
            work,
            demo.id,
            zone,
            workKnown,
            "Product sync",
            at(nextMonday, 10, 0, zone),
            at(nextMonday, 10, 45, zone),
            location = "Video call",
        )
        seedEvent(
            work,
            demo.id,
            zone,
            workKnown,
            "Quarterly planning",
            at(nextTuesday, 13, 0, zone),
            at(nextTuesday, 14, 0, zone),
            location = "Board room",
        )
        seedEvent(
            work,
            demo.id,
            zone,
            workKnown,
            "Team offsite",
            lastWednesday.atStartOfDayIn(zone),
            lastWednesday.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone),
            allDay = true,
            location = "Riverside lodge",
        )

        val personalKnown = knownEvents(personal, demo.id)
        seedEvent(
            personal,
            demo.id,
            zone,
            personalKnown,
            "Coffee with Sam",
            at(today, 15, 0, zone),
            at(today, 15, 30, zone),
            location = "Café Central",
        )
        seedEvent(
            personal,
            demo.id,
            zone,
            personalKnown,
            "Lunch with Sam",
            at(friday, 12, 30, zone),
            at(friday, 13, 30, zone),
            location = "Café Nord",
        )
        listOf(monday, wednesday, friday).forEach { day ->
            seedEvent(
                personal,
                demo.id,
                zone,
                personalKnown,
                "Gym",
                at(day, 18, 30, zone),
                at(day, 19, 30, zone),
                location = "FitX",
            )
        }
        seedEvent(
            personal,
            demo.id,
            zone,
            personalKnown,
            "Yoga",
            at(saturday, 10, 0, zone),
            at(saturday, 11, 0, zone),
            location = "Studio Om",
        )

        val familyKnown = knownEvents(family, demo.id)
        seedEvent(
            family,
            demo.id,
            zone,
            familyKnown,
            "Dinner at Mom's",
            at(sunday, 18, 0, zone),
            at(sunday, 20, 0, zone),
            location = "Mom's place",
        )
        seedEvent(
            family,
            demo.id,
            zone,
            familyKnown,
            "Weekend trip to the lake",
            saturday.atStartOfDayIn(zone),
            nextMonday.atStartOfDayIn(zone),
            allDay = true,
            location = "Lake house",
        )

        val birthdayDate = (0..6)
            .map { weekStart.plus(it, DateTimeUnit.DAY) }
            .firstOrNull { it > today }
            ?: today
        val birthdaysKnown = knownEvents(birthdays, demo.id)
        seedEvent(
            birthdays,
            demo.id,
            zone,
            birthdaysKnown,
            "Sam's birthday",
            birthdayDate.atStartOfDayIn(zone),
            birthdayDate.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone),
            allDay = true,
            recurrence = Recurrence(RecurrenceFrequency.YEARLY, 1),
        )
    }

    private suspend fun seedCommunityEvents(
        sam: User,
        zone: TimeZone,
        weekStart: LocalDate,
        community: Calendar,
    ) {
        val friday = weekStart.plus(4, DateTimeUnit.DAY)
        val saturday = weekStart.plus(5, DateTimeUnit.DAY)
        val known = knownEvents(community, sam.id)
        seedEvent(
            community,
            sam.id,
            zone,
            known,
            "Open mic night",
            at(friday, 19, 0, zone),
            at(friday, 21, 0, zone),
            description = "Bring an instrument or just listen.",
            location = "The Basement",
            url = "https://example.com/open-mic",
        )
        seedEvent(
            community,
            sam.id,
            zone,
            known,
            "Farmers market",
            at(saturday, 9, 0, zone),
            at(saturday, 13, 0, zone),
            location = "Marktplatz",
        )
    }

    private suspend fun seedInvites(
        demo: User,
        sam: User,
        work: Calendar,
        personal: Calendar,
        zone: TimeZone,
    ) {
        val productSync = findEvent(work, demo.id, "Product sync")
        if (productSync != null && attendeeStatus(productSync.id, demo.id) == null) {
            insertAttendee(productSync.id, invitee = demo, inviter = sam)
            notifications.create(
                userId = demo.id,
                kind = "event.invite",
                title = "${sam.displayName} invited you to ${productSync.title}",
                body = whenText(productSync, zone),
                href = "/?date=${productSync.start.toLocalDateTime(zone).date}",
            )
        }
        inviteAndRespond(demo, sam, findEvent(work, demo.id, "Design review"), "maybe")
        inviteAndRespond(demo, sam, findEvent(personal, demo.id, "Lunch with Sam"), "yes")
    }

    private suspend fun inviteAndRespond(inviter: User, invitee: User, event: Event?, status: String) {
        if (event == null) return
        val current = attendeeStatus(event.id, invitee.id)
        if (current == null) {
            invites.invite(event.id, inviter.id, invitee.username)
        }
        if (current == null || current == EventRsvpStatus.INVITED.wire) {
            invites.respond(event.id, invitee.id, status)
        }
    }

    private suspend fun seedFriendships(demo: User, sam: User, alex: User) {
        ensureFriends(demo, alex)
        if (friendships.relationship(demo.id, sam.id) == RelationshipNone) {
            friendships.sendRequest(sam.id, demo.username)
        }
    }

    private suspend fun ensureFriends(a: User, b: User) {
        when (friendships.relationship(a.id, b.id)) {
            RelationshipFriends -> Unit
            RelationshipPendingIn -> {
                val request = friendships.incomingRequests(a.id)
                    .firstOrNull { it.user.userId == b.id.value }
                if (request != null) friendships.accept(a.id, request.id)
            }

            RelationshipPendingOut -> {
                val request = friendships.incomingRequests(b.id)
                    .firstOrNull { it.user.userId == a.id.value }
                if (request != null) friendships.accept(b.id, request.id)
            }

            else -> {
                friendships.sendRequest(a.id, b.username)
                val request = friendships.incomingRequests(b.id)
                    .firstOrNull { it.user.userId == a.id.value }
                if (request != null) friendships.accept(b.id, request.id)
            }
        }
    }

    private suspend fun seedReminders(demo: User, work: Calendar) {
        reminders.updateSettings(demo.id, listOf(600, 3600), notifyAtStart = true)
        val designReview = findEvent(work, demo.id, "Design review") ?: return
        reminders.setEventReminders(demo.id, designReview.id, listOf(1800), useDefaults = false)
    }

    private suspend fun seedHolidays(demo: User) {
        store.setShowHolidays(demo.id, true)
        store.setHolidaySubscriptions(demo.id, listOf("new-years-day", "christmas-day", "good-friday"))
    }

    private suspend fun seedAvailability(demo: User, work: Calendar) {
        availability.updateSettings(
            calendarId = work.id,
            userId = demo.id,
            requestsEnabled = true,
            slotMinutes = 30,
            accessMode = PublicAccessMode.INHERIT,
            windows = (0..4).map { weekday ->
                AvailabilityWindow(weekday = weekday, startMinute = 9 * 60, endMinute = 17 * 60)
            },
        )
    }

    private suspend fun followCommunity(demo: User, sam: User, community: Calendar) {
        val linked = if (community.publicLinkEnabled && community.publicLinkToken != null) {
            community
        } else {
            store.setPublicLink(community.id, sam.id, true) ?: return
        }
        val token = linked.publicLinkToken ?: return
        val alreadyFollowing = store.listCalendars(demo.id).any { it.id == community.id }
        if (!alreadyFollowing) store.follow(token, demo.id)
    }

    private suspend fun ensureUser(
        username: String,
        displayName: String,
        email: String,
        accent: String,
        zone: TimeZone,
    ): User = dbQuery {
        val now = clock.now()
        val existing = UsersTable.selectAll()
            .where { UsersTable.username eq username }
            .singleOrNull()
        if (existing == null) {
            UsersTable.insert {
                it[UsersTable.id] = Uuid.random()
                it[UsersTable.username] = username
                it[passwordHash] = hasher.hash(settings.demoPassword)
                it[UsersTable.displayName] = displayName
                it[timeZone] = zone.id
                it[UsersTable.accent] = accent
                it[isAdmin] = false
                it[createdAt] = now
                it[updatedAt] = now
                it[UsersTable.email] = email
                it[emailVerified] = true
                it[avatarKey] = null
                it[avatarUpdatedAt] = null
            }
            log.info("created demo user '{}'", username)
        } else {
            val passwordChanged = !hasher.matches(settings.demoPassword, existing[UsersTable.passwordHash])
            UsersTable.update({ UsersTable.id eq existing[UsersTable.id] }) {
                it[UsersTable.displayName] = displayName
                it[timeZone] = zone.id
                it[UsersTable.accent] = accent
                it[UsersTable.email] = email
                it[emailVerified] = true
                if (passwordChanged) it[passwordHash] = hasher.hash(settings.demoPassword)
                it[updatedAt] = now
            }
        }
        UsersTable.selectAll()
            .where { UsersTable.username eq username }
            .single()
            .toUser()
    }

    private suspend fun ensureAvatar(user: User, startColor: Int, endColor: Int) {
        if (auth.avatarKey(user.id) != null) return
        runCatching {
            val bytes = avatarPng(startColor, endColor)
            groups.assertCanStore(user.id, bytes.size.toLong())
            val key = "avatars/${user.id.value}/demo.png"
            storage.put(key, bytes, "image/png")
            auth.setAvatar(user.id, key, bytes.size.toLong())
        }.onFailure { error ->
            log.warn("could not seed avatar for '{}': {}", user.username, error.message)
        }
    }

    private suspend fun knownEvents(calendar: Calendar, userId: UserId): MutableSet<String> =
        store.listEvents(calendar.id, userId).mapTo(mutableSetOf()) { eventKey(it.title, it.start) }

    private suspend fun findEvent(calendar: Calendar, userId: UserId, title: String): Event? =
        store.listEvents(calendar.id, userId).firstOrNull { it.title == title }

    private suspend fun seedEvent(
        calendar: Calendar,
        ownerId: UserId,
        zone: TimeZone,
        known: MutableSet<String>,
        title: String,
        start: Instant,
        end: Instant,
        description: String? = null,
        location: String? = null,
        url: String? = null,
        allDay: Boolean = false,
        status: EventStatus = EventStatus.CONFIRMED,
        recurrence: Recurrence? = null,
    ): Event? {
        if (!known.add(eventKey(title, start))) return null
        return store.createEvent(
            calendar.id,
            ownerId,
            CreateEvent(
                title = title,
                description = description,
                location = location,
                url = url,
                start = start,
                end = end,
                allDay = allDay,
                timeZone = zone.id,
                status = status,
                recurrence = recurrence,
            ),
        )
    }

    private suspend fun attendeeStatus(eventId: EventId, userId: UserId): String? = dbQuery {
        EventAttendeesTable.selectAll()
            .where {
                (EventAttendeesTable.eventId eq Uuid.parse(eventId.value)) and
                    (EventAttendeesTable.userId eq Uuid.parse(userId.value))
            }
            .singleOrNull()
            ?.get(EventAttendeesTable.status)
    }

    private suspend fun insertAttendee(eventId: EventId, invitee: User, inviter: User) {
        dbQuery {
            EventAttendeesTable.insert {
                it[id] = Uuid.random()
                it[EventAttendeesTable.eventId] = Uuid.parse(eventId.value)
                it[userId] = Uuid.parse(invitee.id.value)
                it[email] = invitee.email
                it[name] = invitee.displayName
                it[status] = EventRsvpStatus.INVITED.wire
                it[invitedBy] = Uuid.parse(inviter.id.value)
                it[tokenHash] = null
                it[createdAt] = clock.now()
                it[respondedAt] = null
            }
        }
    }

    private fun demoZone(): TimeZone = runCatching { TimeZone.of(settings.demoTimezone) }
        .getOrElse {
            log.warn("unknown app.demoTimezone '{}'; falling back to Europe/Berlin", settings.demoTimezone)
            TimeZone.of("Europe/Berlin")
        }

    private fun avatarPng(startColor: Int, endColor: Int): ByteArray {
        val size = AvatarSize
        val stride = 1 + size * 3
        val raw = ByteArray(size * stride)
        var index = 0
        val last = (size - 1).toFloat()
        for (y in 0 until size) {
            raw[index++] = 0
            for (x in 0 until size) {
                val pixel = blend(startColor, endColor, (x + y) / (2f * last))
                raw[index++] = ((pixel shr 16) and 0xFF).toByte()
                raw[index++] = ((pixel shr 8) and 0xFF).toByte()
                raw[index++] = (pixel and 0xFF).toByte()
            }
        }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.write(PngSignature)
            writePngChunk(data, "IHDR", ihdr(size))
            writePngChunk(data, "IDAT", deflate(raw))
            writePngChunk(data, "IEND", ByteArray(0))
        }
        return out.toByteArray()
    }

    private fun ihdr(size: Int): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(size)
            data.writeInt(size)
            data.writeByte(8)
            data.writeByte(2)
            data.writeByte(0)
            data.writeByte(0)
            data.writeByte(0)
        }
        return out.toByteArray()
    }

    private fun writePngChunk(data: DataOutputStream, type: String, payload: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        data.writeInt(payload.size)
        data.write(typeBytes)
        data.write(payload)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(payload)
        data.writeInt(crc.value.toInt())
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    private fun blend(startColor: Int, endColor: Int, t: Float): Int {
        val ratio = t.coerceIn(0f, 1f)
        val red = channel(startColor, 16) + ((channel(endColor, 16) - channel(startColor, 16)) * ratio).toInt()
        val green = channel(startColor, 8) + ((channel(endColor, 8) - channel(startColor, 8)) * ratio).toInt()
        val blue = channel(startColor, 0) + ((channel(endColor, 0) - channel(startColor, 0)) * ratio).toInt()
        return (red shl 16) or (green shl 8) or blue
    }

    private fun channel(color: Int, shift: Int): Int = (color shr shift) and 0xFF

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }

    private fun eventKey(title: String, start: Instant): String = "$title@$start"

    private fun at(date: LocalDate, hour: Int, minute: Int, zone: TimeZone): Instant =
        LocalDateTime(date.year, date.month, date.day, hour, minute).toInstant(zone)

    private fun whenText(event: Event, zone: TimeZone): String {
        val start = event.start.toLocalDateTime(zone)
        val end = event.end.toLocalDateTime(zone)
        return "${start.date} ${timeText(start)}-${timeText(end)} (${zone.id})"
    }

    private fun timeText(local: LocalDateTime): String =
        "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
}

private const val AvatarSize = 256

private val PngSignature = byteArrayOf(
    0x89.toByte(),
    0x50,
    0x4E,
    0x47,
    0x0D,
    0x0A,
    0x1A,
    0x0A,
)

private data class DemoCalendarSpec(val name: String, val color: String, val description: String)

private val DemoCalendarSpecs = listOf(
    DemoCalendarSpec("Work", "primary", "Meetings, deep work, and deadlines."),
    DemoCalendarSpec("Personal", "success", "Gym, errands, and everything else."),
    DemoCalendarSpec("Family", "warning", "Family plans and dinners."),
    DemoCalendarSpec("Birthdays", "accent", "Birthdays to remember."),
)

private val RequiredEventTitles = mapOf(
    "Work" to listOf(
        "Team standup",
        "Focus time",
        "Design review",
        "Roadmap planning",
        "Interview: backend candidate",
        "1:1 with Sam",
        "Product sync",
        "Quarterly planning",
        "Team offsite",
    ),
    "Personal" to listOf("Coffee with Sam", "Lunch with Sam", "Gym", "Yoga"),
    "Family" to listOf("Dinner at Mom's", "Weekend trip to the lake"),
    "Birthdays" to listOf("Sam's birthday"),
)
