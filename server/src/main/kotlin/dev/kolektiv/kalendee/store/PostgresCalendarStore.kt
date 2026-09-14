package dev.kolektiv.kalendee.store

import dev.kolektiv.kalendee.auth.PublicAccessMode
import dev.kolektiv.kalendee.auth.SessionTokens
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CalendarShare
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateCustomHoliday
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.CustomHoliday
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.calendar.EventRsvpStatus
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.calendar.HolidayPrefs
import dev.kolektiv.kalendee.calendar.InstantRange
import dev.kolektiv.kalendee.calendar.OrganizationId
import dev.kolektiv.kalendee.calendar.OrganizationTeamId
import dev.kolektiv.kalendee.calendar.Recurrence
import dev.kolektiv.kalendee.calendar.RecurrenceFrequency
import dev.kolektiv.kalendee.calendar.UpdateCalendar
import dev.kolektiv.kalendee.calendar.UpdateEvent
import dev.kolektiv.kalendee.calendar.colorFor
import dev.kolektiv.kalendee.calendar.getOrElse
import dev.kolektiv.kalendee.calendar.occurrencesIn
import dev.kolektiv.kalendee.calendar.requireKnownHolidayIds
import dev.kolektiv.kalendee.calendar.splitAt
import dev.kolektiv.kalendee.calendar.validated
import dev.kolektiv.kalendee.db.CalendarFollowersTable
import dev.kolektiv.kalendee.db.CalendarHiddenTable
import dev.kolektiv.kalendee.db.CalendarSharesTable
import dev.kolektiv.kalendee.db.CalendarTeamGrantsTable
import dev.kolektiv.kalendee.db.CalendarsTable
import dev.kolektiv.kalendee.db.CustomHolidaysTable
import dev.kolektiv.kalendee.db.EventAttendeesTable
import dev.kolektiv.kalendee.db.EventReminderSettingsTable
import dev.kolektiv.kalendee.db.EventRemindersTable
import dev.kolektiv.kalendee.db.EventsTable
import dev.kolektiv.kalendee.db.ExternalCalendarsTable
import dev.kolektiv.kalendee.db.HolidaySubscriptionsTable
import dev.kolektiv.kalendee.db.OrganizationMembersTable
import dev.kolektiv.kalendee.db.OrganizationTeamMembersTable
import dev.kolektiv.kalendee.db.OrganizationTeamsTable
import dev.kolektiv.kalendee.db.OrganizationsTable
import dev.kolektiv.kalendee.db.UsersTable
import dev.kolektiv.kalendee.organizations.OrganizationRole
import dev.kolektiv.kalendee.organizations.OrganizationTeamRole
import dev.kolektiv.kalendee.organizations.OrganizationTeamService
import dev.kolektiv.kalendee.organizations.permissionRank
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

class PostgresCalendarStore(
    private val database: Database,
    private val teams: OrganizationTeamService,
    private val clock: Clock = Clock.System,
) : CalendarStore {
    override suspend fun ping() {
        dbQuery<Unit> {
            exec("SELECT 1")
        }
    }

    override suspend fun listCalendars(userId: UserId): List<Calendar> = dbQuery {
        val hidden = hiddenIds(userId)
        val owned = CalendarsTable.selectAll()
            .where { CalendarsTable.ownerId eq userId.toUuid() }
            .map { it[CalendarsTable.id] }
            .toSet()
        val shared = CalendarSharesTable.selectAll()
            .where { CalendarSharesTable.userId eq userId.toUuid() }
            .associate { it[CalendarSharesTable.calendarId] to it[CalendarSharesTable.permission] }
        val followed = CalendarFollowersTable.selectAll()
            .where { CalendarFollowersTable.userId eq userId.toUuid() }
            .map { it[CalendarFollowersTable.calendarId] }
            .toSet()
        val permissions = mutableMapOf<Uuid, CalendarPermission>()
        followed.forEach { permissions[it] = CalendarPermission.FOLLOW }
        shared.forEach { (calendarId, wire) ->
            val permission = CalendarPermission.fromWire(wire) ?: CalendarPermission.READ
            permissions[calendarId] = strongest(permissions[calendarId], permission)
        }
        organizationCalendars(userId).forEach { (calendarId, permission) ->
            permissions[calendarId] = strongest(permissions[calendarId], permission)
        }
        owned.forEach { permissions[it] = CalendarPermission.OWNER }
        val ids = permissions.keys.toList()
        if (ids.isEmpty()) return@dbQuery emptyList()
        (CalendarsTable innerJoin UsersTable)
            .selectAll()
            .where { CalendarsTable.id inList ids }
            .orderBy(CalendarsTable.displayName to SortOrder.ASC, CalendarsTable.id to SortOrder.ASC)
            .map { row ->
                val id = row[CalendarsTable.id]
                val permission = permissions[id] ?: CalendarPermission.FOLLOW
                row.toCalendar(
                    permission = permission,
                    hidden = CalendarId(id.toString()) in hidden,
                    ownerScoped = permission == CalendarPermission.OWNER,
                    ownerAvatarVersion = row[UsersTable.avatarUpdatedAt]?.toEpochMilliseconds(),
                )
            }
    }

    override suspend fun getCalendar(id: CalendarId, userId: UserId): Calendar? = dbQuery {
        accessibleCalendar(id, userId)
    }

    override suspend fun createCalendar(ownerId: UserId, command: CreateCalendar): Calendar {
        val now = clock.now()
        val id = CalendarId.generate()
        return dbQuery {
            val ownerZone = UsersTable.selectAll()
                .where { UsersTable.id eq ownerId.toUuid() }
                .single()[UsersTable.timeZone]
            val validated = command.validated(ownerZone)
            val zone = requireNotNull(validated.timeZone)
            val color = validated.color ?: colorFor(id.value)
            val organizationId = command.organizationId
            if (organizationId != null) {
                val orgExists = OrganizationsTable.selectAll()
                    .where { OrganizationsTable.id eq organizationId.toUuid() }
                    .count() > 0
                if (!orgExists) throw CalendarException.NotFound("organization not found")
                val isMember = OrganizationMembersTable.selectAll()
                    .where {
                        (OrganizationMembersTable.organizationId eq organizationId.toUuid()) and
                            (OrganizationMembersTable.userId eq ownerId.toUuid())
                    }
                    .count() > 0
                if (!isMember) throw CalendarException.Forbidden("not a member of this organization")
            }
            CalendarsTable.insert {
                it[CalendarsTable.id] = id.toUuid()
                it[CalendarsTable.ownerId] = ownerId.toUuid()
                it[CalendarsTable.organizationId] = organizationId?.toUuid()
                it[displayName] = validated.displayName
                it[description] = validated.description
                it[timeZone] = zone
                it[CalendarsTable.color] = color
                it[createdAt] = now
                it[updatedAt] = now
            }
            if (organizationId != null) {
                teams.grantAllTeamWriteInTransaction(this, id, organizationId)
            }
            Calendar(
                id = id,
                ownerId = ownerId,
                displayName = validated.displayName,
                description = validated.description,
                timeZone = zone,
                color = color,
                createdAt = now,
                updatedAt = now,
                organizationId = organizationId,
            )
        }
    }

    override suspend fun updateCalendar(
        id: CalendarId,
        ownerId: UserId,
        command: UpdateCalendar,
    ): Calendar? {
        val validated = command.validated()
        return dbQuery {
            val existing = manageableCalendar(id, ownerId) ?: return@dbQuery null
            val now = clock.now()
            val updated = existing.copy(
                displayName = validated.displayName ?: existing.displayName,
                description = validated.description.getOrElse(existing.description),
                timeZone = validated.timeZone ?: existing.timeZone,
                color = validated.color ?: existing.color,
                updatedAt = now,
            )
            CalendarsTable.update({ manageableCalendarOp(id, ownerId) }) {
                it[displayName] = updated.displayName
                it[description] = updated.description
                it[timeZone] = updated.timeZone
                it[color] = updated.color
                it[updatedAt] = now
            }
            updated
        }
    }

    override suspend fun deleteCalendar(id: CalendarId, ownerId: UserId): Boolean = dbQuery {
        CalendarsTable.deleteWhere { manageableCalendarOp(id, ownerId) } > 0
    }

    override suspend fun setCalendarHidden(id: CalendarId, userId: UserId, hidden: Boolean): Calendar? =
        dbQuery {
            val existing = accessibleCalendar(id, userId) ?: return@dbQuery null
            val alreadyHidden = CalendarHiddenTable.selectAll()
                .where {
                    (CalendarHiddenTable.userId eq userId.toUuid()) and
                        (CalendarHiddenTable.calendarId eq id.toUuid())
                }
                .count() > 0
            if (hidden && !alreadyHidden) {
                CalendarHiddenTable.insert {
                    it[CalendarHiddenTable.userId] = userId.toUuid()
                    it[CalendarHiddenTable.calendarId] = id.toUuid()
                }
            } else if (!hidden && alreadyHidden) {
                CalendarHiddenTable.deleteWhere {
                    (CalendarHiddenTable.userId eq userId.toUuid()) and
                        (CalendarHiddenTable.calendarId eq id.toUuid())
                }
            }
            existing.copy(hidden = hidden)
        }

    override suspend fun transferCalendar(
        id: CalendarId,
        actorId: UserId,
        destinationOrganizationId: OrganizationId?,
        destinationTeamId: OrganizationTeamId?,
    ): Calendar? = dbQuery {
        val row = CalendarsTable.selectAll()
            .where { CalendarsTable.id eq id.toUuid() }
            .singleOrNull()
            ?: return@dbQuery null

        val actor = actorId.toUuid()
        val isCalendarOwner = row[CalendarsTable.ownerId] == actor
        val sourceOrganizationId = row[CalendarsTable.organizationId]
        val sourceRole = sourceOrganizationId?.let { orgRole(it, actorId) }
        val managesSource = isCalendarOwner || isOrganizationManager(sourceRole)
        if (!managesSource) throw CalendarException.Forbidden("cannot transfer this calendar")

        var destinationOrgId = destinationOrganizationId
        if (destinationOrganizationId != null) {
            val organizationExists = OrganizationsTable.selectAll()
                .where { OrganizationsTable.id eq destinationOrganizationId.toUuid() }
                .count() > 0
            if (!organizationExists) throw CalendarException.NotFound("organization not found")
            if (orgRole(destinationOrganizationId.toUuid(), actorId) == null) {
                throw CalendarException.Forbidden("not a member of this organization")
            }
        } else if (sourceOrganizationId != null && !isCalendarOwner && !isOrganizationManager(sourceRole)) {
            throw CalendarException.Forbidden("only owners and admins can move an organization calendar")
        }

        var teamGrantId: OrganizationTeamId? = null
        if (destinationTeamId != null) {
            val teamRow = OrganizationTeamsTable.selectAll()
                .where { OrganizationTeamsTable.id eq destinationTeamId.toUuid() }
                .singleOrNull()
                ?: throw CalendarException.NotFound("team not found")
            val teamOrganizationId = OrganizationId(teamRow[OrganizationTeamsTable.organizationId].toString())
            if (destinationOrgId != null && destinationOrgId != teamOrganizationId) {
                throw CalendarException.Invalid("team is not part of this organization")
            }
            destinationOrgId = teamOrganizationId
            if (!canManageTeam(teamOrganizationId, destinationTeamId, actorId)) {
                throw CalendarException.Forbidden("cannot manage this team")
            }
            teamGrantId = destinationTeamId
        }

        if (hasExternalCalendar(id.toUuid())) {
            throw CalendarException.Forbidden("disconnect sync before moving this calendar")
        }

        val now = clock.now()
        CalendarsTable.update({ CalendarsTable.id eq id.toUuid() }) {
            it[organizationId] = destinationOrgId?.toUuid()
            it[ownerId] = actor
            it[updatedAt] = now
        }

        if (teamGrantId != null) {
            CalendarTeamGrantsTable.deleteWhere { CalendarTeamGrantsTable.calendarId eq id.toUuid() }
            CalendarTeamGrantsTable.insert {
                it[CalendarTeamGrantsTable.calendarId] = id.toUuid()
                it[CalendarTeamGrantsTable.teamId] = teamGrantId.toUuid()
                it[CalendarTeamGrantsTable.permission] = CalendarPermission.WRITE.wire
                it[createdAt] = now
            }
        } else if (destinationOrgId == null) {
            CalendarTeamGrantsTable.deleteWhere { CalendarTeamGrantsTable.calendarId eq id.toUuid() }
        } else {
            replaceWithDefaultTeamWrite(id, destinationOrgId, actorId)
        }

        CalendarsTable.selectAll()
            .where { CalendarsTable.id eq id.toUuid() }
            .single()
            .toCalendar(
                permission = CalendarPermission.OWNER,
                hidden = id in hiddenIds(actorId),
                ownerScoped = true,
            )
    }

    override suspend fun listShares(calendarId: CalendarId, ownerId: UserId): List<CalendarShare> = dbQuery {
        if (manageableCalendar(calendarId, ownerId) == null) return@dbQuery emptyList()
        (CalendarSharesTable innerJoin UsersTable)
            .selectAll()
            .where { CalendarSharesTable.calendarId eq calendarId.toUuid() }
            .orderBy(UsersTable.username to SortOrder.ASC)
            .map { it.toCalendarShare() }
    }

    override suspend fun addShare(
        calendarId: CalendarId,
        ownerId: UserId,
        inviteeId: UserId,
        permission: CalendarPermission,
    ): CalendarShare {
        if (inviteeId == ownerId) {
            throw CalendarException.Invalid("cannot share a calendar with its owner")
        }
        return dbQuery {
            manageableCalendar(calendarId, ownerId)
                ?: throw CalendarException.NotFound("calendar not found")
            val now = clock.now()
            val exists = CalendarSharesTable.selectAll()
                .where {
                    (CalendarSharesTable.calendarId eq calendarId.toUuid()) and
                        (CalendarSharesTable.userId eq inviteeId.toUuid())
                }
                .count() > 0
            if (exists) {
                CalendarSharesTable.update({
                    (CalendarSharesTable.calendarId eq calendarId.toUuid()) and
                        (CalendarSharesTable.userId eq inviteeId.toUuid())
                }) {
                    it[CalendarSharesTable.permission] = permission.wire
                }
            } else {
                CalendarSharesTable.insert {
                    it[CalendarSharesTable.calendarId] = calendarId.toUuid()
                    it[CalendarSharesTable.userId] = inviteeId.toUuid()
                    it[CalendarSharesTable.permission] = permission.wire
                    it[createdAt] = now
                }
            }
            shareRow(calendarId, inviteeId)
        }
    }

    override suspend fun updateShare(
        calendarId: CalendarId,
        ownerId: UserId,
        inviteeId: UserId,
        permission: CalendarPermission,
    ): CalendarShare? = dbQuery {
        if (manageableCalendar(calendarId, ownerId) == null) return@dbQuery null
        val updated = CalendarSharesTable.update({
            (CalendarSharesTable.calendarId eq calendarId.toUuid()) and
                (CalendarSharesTable.userId eq inviteeId.toUuid())
        }) {
            it[CalendarSharesTable.permission] = permission.wire
        }
        if (updated == 0) return@dbQuery null
        shareRow(calendarId, inviteeId)
    }

    override suspend fun removeShare(
        calendarId: CalendarId,
        ownerId: UserId,
        inviteeId: UserId,
    ): Boolean = dbQuery {
        if (manageableCalendar(calendarId, ownerId) == null) return@dbQuery false
        CalendarSharesTable.deleteWhere {
            (CalendarSharesTable.calendarId eq calendarId.toUuid()) and
                (CalendarSharesTable.userId eq inviteeId.toUuid())
        } > 0
    }

    override suspend fun setPublicLink(
        calendarId: CalendarId,
        ownerId: UserId,
        enabled: Boolean,
    ): Calendar? = dbQuery {
        val existing = manageableCalendar(calendarId, ownerId) ?: return@dbQuery null
        val token = when {
            enabled -> existing.publicLinkToken ?: SessionTokens.generate()
            else -> existing.publicLinkToken
        }
        CalendarsTable.update({ manageableCalendarOp(calendarId, ownerId) }) {
            it[publicLinkEnabled] = enabled
            it[publicLinkToken] = token
        }
        existing.copy(publicLinkEnabled = enabled, publicLinkToken = token)
    }

    override suspend fun rotatePublicLink(calendarId: CalendarId, ownerId: UserId): Calendar? = dbQuery {
        val existing = manageableCalendar(calendarId, ownerId) ?: return@dbQuery null
        val token = SessionTokens.generate()
        CalendarsTable.update({ manageableCalendarOp(calendarId, ownerId) }) {
            it[publicLinkToken] = token
        }
        existing.copy(publicLinkToken = token)
    }

    override suspend fun publicCalendar(token: String): Calendar? = dbQuery {
        (CalendarsTable innerJoin UsersTable)
            .selectAll()
            .where {
                (CalendarsTable.publicLinkEnabled eq true) and
                    (CalendarsTable.publicLinkToken eq token)
            }
            .singleOrNull()
            ?.let { row ->
                row.toCalendar(
                    permission = CalendarPermission.READ,
                    ownerAvatarVersion = row[UsersTable.avatarUpdatedAt]?.toEpochMilliseconds(),
                )
            }
    }

    override suspend fun listPublicEvents(calendarId: CalendarId, range: InstantRange?): List<Event> = dbQuery {
        val zone = CalendarsTable.selectAll()
            .where { CalendarsTable.id eq calendarId.toUuid() }
            .singleOrNull()
            ?.get(CalendarsTable.timeZone)
            ?: return@dbQuery emptyList()
        eventsFor(calendarId.toUuid(), range, zone)
    }

    override suspend fun follow(token: String, userId: UserId): Calendar? = dbQuery {
        val row = CalendarsTable.selectAll()
            .where {
                (CalendarsTable.publicLinkEnabled eq true) and
                    (CalendarsTable.publicLinkToken eq token)
            }
            .singleOrNull()
            ?: return@dbQuery null
        val calendarId = row[CalendarsTable.id]
        if (row[CalendarsTable.ownerId] == userId.toUuid()) return@dbQuery null
        val organizationRole = row[CalendarsTable.organizationId]?.let { organizationId ->
            orgRole(organizationId, userId)
        }
        if (organizationRole == OrganizationRole.OWNER || organizationRole == OrganizationRole.ADMIN) {
            return@dbQuery null
        }
        val exists = CalendarFollowersTable.selectAll()
            .where {
                (CalendarFollowersTable.calendarId eq calendarId) and
                    (CalendarFollowersTable.userId eq userId.toUuid())
            }
            .count() > 0
        if (!exists) {
            CalendarFollowersTable.insert {
                it[CalendarFollowersTable.calendarId] = calendarId
                it[CalendarFollowersTable.userId] = userId.toUuid()
                it[createdAt] = clock.now()
            }
        }
        row.toCalendar(permission = CalendarPermission.FOLLOW)
    }

    override suspend fun unfollow(calendarId: CalendarId, userId: UserId): Boolean = dbQuery {
        CalendarFollowersTable.deleteWhere {
            (CalendarFollowersTable.calendarId eq calendarId.toUuid()) and
                (CalendarFollowersTable.userId eq userId.toUuid())
        } > 0
    }

    override suspend fun countFollowers(calendarId: CalendarId): Int = dbQuery {
        CalendarFollowersTable.selectAll()
            .where { CalendarFollowersTable.calendarId eq calendarId.toUuid() }
            .count()
            .toInt()
    }

    override suspend fun holidayPrefs(ownerId: UserId): HolidayPrefs = dbQuery {
        prefs(ownerId)
    }

    override suspend fun setShowHolidays(ownerId: UserId, show: Boolean): HolidayPrefs = dbQuery {
        UsersTable.update({ UsersTable.id eq ownerId.toUuid() }) {
            it[showHolidays] = show
        }
        prefs(ownerId)
    }

    override suspend fun setHolidaySubscriptions(ownerId: UserId, holidayIds: List<String>): HolidayPrefs {
        val unique = requireKnownHolidayIds(holidayIds)
        return dbQuery {
            HolidaySubscriptionsTable.deleteWhere { HolidaySubscriptionsTable.userId eq ownerId.toUuid() }
            unique.forEach { id ->
                HolidaySubscriptionsTable.insert {
                    it[userId] = ownerId.toUuid()
                    it[holidayId] = id
                }
            }
            prefs(ownerId)
        }
    }

    override suspend fun createCustomHoliday(ownerId: UserId, command: CreateCustomHoliday): CustomHoliday {
        val validated = command.validated()
        val id = Uuid.random().toString()
        val now = clock.now()
        return dbQuery {
            CustomHolidaysTable.insert {
                it[CustomHolidaysTable.id] = Uuid.parse(id)
                it[userId] = ownerId.toUuid()
                it[title] = validated.title
                it[month] = validated.month
                it[day] = validated.day
                it[createdAt] = now
            }
            CustomHoliday(id = id, title = validated.title, month = validated.month, day = validated.day)
        }
    }

    override suspend fun deleteCustomHoliday(id: String, ownerId: UserId): Boolean {
        val parsed = Uuid.parseOrNull(id) ?: throw CalendarException.Invalid("invalid holiday id")
        return dbQuery {
            CustomHolidaysTable.deleteWhere {
                (CustomHolidaysTable.id eq parsed) and (CustomHolidaysTable.userId eq ownerId.toUuid())
            } > 0
        }
    }

    override suspend fun listEvents(
        calendarId: CalendarId,
        userId: UserId,
        range: InstantRange?,
    ): List<Event> {
        return dbQuery {
            val calendar = accessibleCalendar(calendarId, userId)
                ?: throw CalendarException.NotFound("calendar not found")
            eventsFor(calendarId.toUuid(), range, calendar.timeZone, userId)
        }
    }

    override suspend fun listEvents(userId: UserId, range: InstantRange): List<Event> = dbQuery {
        val hidden = hiddenIds(userId).map { it.toUuid() }.toSet()
        val accessible = accessibleCalendarIds(userId).filter { it !in hidden }
        val invited = EventAttendeesTable.selectAll()
            .where { EventAttendeesTable.userId eq userId.toUuid() }
            .map { it[EventAttendeesTable.eventId] }
            .toSet()
        if (accessible.isEmpty() && invited.isEmpty()) return@dbQuery emptyList()
        val accessMatch = when {
            accessible.isEmpty() -> EventsTable.id inList invited.toList()
            invited.isEmpty() -> CalendarsTable.id inList accessible
            else -> (CalendarsTable.id inList accessible) or (EventsTable.id inList invited.toList())
        }
        val visibleMatch = if (hidden.isEmpty()) {
            accessMatch
        } else {
            accessMatch and (CalendarsTable.id notInList hidden.toList())
        }
        val rows = (EventsTable innerJoin CalendarsTable)
            .selectAll()
            .where { visibleMatch and eventOverlaps(range) }
            .orderBy(EventsTable.startAt to SortOrder.ASC, EventsTable.id to SortOrder.ASC)
            .toList()
        val statuses = rsvpStatuses(userId, rows.map { it[EventsTable.id] })
        rows.flatMap { row ->
            val event = row.toEvent(rsvpStatus = statuses[row[EventsTable.id]]?.wire)
            val zone = event.timeZone ?: row[CalendarsTable.timeZone]
            event.occurrencesIn(range, TimeZone.of(zone))
        }.sortedWith(compareBy({ it.start }, { it.id.value }))
    }

    override suspend fun getEvent(id: EventId, userId: UserId): Event? = dbQuery {
        val row = (EventsTable innerJoin CalendarsTable)
            .selectAll()
            .where { EventsTable.id eq id.toUuid() }
            .singleOrNull()
            ?: return@dbQuery null
        val status = rsvpStatuses(userId, listOf(id.toUuid()))[id.toUuid()]
        if (permissionFor(row[EventsTable.calendarId], userId) == null && status == null) {
            return@dbQuery null
        }
        row.toEvent(rsvpStatus = status?.wire)
    }

    override suspend fun createEvent(
        calendarId: CalendarId,
        userId: UserId,
        command: CreateEvent,
    ): Event {
        val validated = command.validated()
        val now = clock.now()
        val id = EventId.generate()
        val etag = newEtag()
        return dbQuery {
            val permission = permissionFor(calendarId.toUuid(), userId)
                ?: throw CalendarException.NotFound("calendar not found")
            if (!permission.canWrite) throw CalendarException.Forbidden("read-only calendar")
            requireNotMirrored(calendarId.toUuid())
            val calendar = CalendarsTable.selectAll()
                .where { CalendarsTable.id eq calendarId.toUuid() }
                .single()
            val zone = validated.timeZone ?: calendar[CalendarsTable.timeZone]
            EventsTable.insert {
                it[EventsTable.id] = id.toUuid()
                it[EventsTable.calendarId] = calendarId.toUuid()
                it[title] = validated.title
                it[description] = validated.description
                it[location] = validated.location
                it[url] = validated.url
                it[startAt] = validated.start
                it[endAt] = validated.end
                it[allDay] = validated.allDay
                it[timeZone] = zone
                it[status] = validated.status.name
                it[recurrenceFrequency] = validated.recurrence?.frequency?.name
                it[recurrenceInterval] = validated.recurrence?.interval
                it[recurrenceUntil] = validated.recurrence?.until
                it[recurrenceCount] = validated.recurrence?.count
                it[EventsTable.etag] = etag
                it[createdAt] = now
                it[updatedAt] = now
            }
            Event(
                id = id,
                calendarId = calendarId,
                title = validated.title,
                description = validated.description,
                location = validated.location,
                url = validated.url,
                start = validated.start,
                end = validated.end,
                allDay = validated.allDay,
                timeZone = zone,
                status = validated.status,
                recurrence = validated.recurrence,
                etag = etag,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    override suspend fun updateEvent(
        id: EventId,
        userId: UserId,
        command: UpdateEvent,
        expectedEtag: String?,
    ): Event? {
        return dbQuery {
            val row = (EventsTable innerJoin CalendarsTable)
                .selectAll()
                .where { EventsTable.id eq id.toUuid() }
                .singleOrNull()
                ?: return@dbQuery null
            val permission = permissionFor(row[EventsTable.calendarId], userId)
                ?: return@dbQuery null
            if (!permission.canWrite) throw CalendarException.Forbidden("read-only calendar")
            // A writer on the calendar may edit imported/synced events. The
            // change becomes a sticky local override (locally_modified_at) so
            // the next provider sync does not clobber it. Deletion stays
            // guarded in deleteEvent because a sync would resurrect the row.
            val existing = row.toEvent()
            existing.requireEtag(expectedEtag)
            val imported = row[EventsTable.externalCalendarId] != null
            val validated = command.validated(existing.start, existing.end)
            val now = clock.now()
            val etag = newEtag()
            val updated = existing.copy(
                title = validated.title ?: existing.title,
                description = validated.description.getOrElse(existing.description),
                location = validated.location.getOrElse(existing.location),
                url = validated.url.getOrElse(existing.url),
                start = validated.start ?: existing.start,
                end = validated.end ?: existing.end,
                allDay = validated.allDay ?: existing.allDay,
                timeZone = validated.timeZone.getOrElse(existing.timeZone),
                status = validated.status ?: existing.status,
                recurrence = validated.recurrence.getOrElse(existing.recurrence),
                etag = etag,
                updatedAt = now,
            )
            EventsTable.update({ EventsTable.id eq id.toUuid() }) {
                it[title] = updated.title
                it[description] = updated.description
                it[location] = updated.location
                it[url] = updated.url
                it[startAt] = updated.start
                it[endAt] = updated.end
                it[allDay] = updated.allDay
                it[timeZone] = updated.timeZone
                it[status] = updated.status.name
                it[recurrenceFrequency] = updated.recurrence?.frequency?.name
                it[recurrenceInterval] = updated.recurrence?.interval
                it[recurrenceUntil] = updated.recurrence?.until
                it[recurrenceCount] = updated.recurrence?.count
                it[EventsTable.etag] = etag
                it[updatedAt] = now
                if (imported) it[EventsTable.locallyModifiedAt] = now
            }
            updated
        }
    }

    override suspend fun moveEvent(
        id: EventId,
        userId: UserId,
        destinationCalendarId: CalendarId,
        occurrenceStart: Instant?,
        expectedEtag: String?,
    ): List<Event>? = dbQuery {
        val row = (EventsTable innerJoin CalendarsTable)
            .selectAll()
            .where { EventsTable.id eq id.toUuid() }
            .singleOrNull()
            ?: return@dbQuery null
        val sourcePermission = permissionFor(row[EventsTable.calendarId], userId)
            ?: return@dbQuery null
        if (!sourcePermission.canWrite) throw CalendarException.Forbidden("read-only calendar")
        val destinationPermission = permissionFor(destinationCalendarId.toUuid(), userId)
            ?: throw CalendarException.NotFound("calendar not found")
        if (!destinationPermission.canWrite) throw CalendarException.Forbidden("read-only calendar")
        // The destination still has to be locally writable: importing into a
        // provider mirror would be overwritten by the next sync.
        requireNotMirrored(destinationCalendarId.toUuid())
        if (destinationCalendarId.toUuid() == row[EventsTable.calendarId]) {
            throw CalendarException.Invalid("event is already in that calendar")
        }
        val existing = row.toEvent()
        existing.requireEtag(expectedEtag)
        // Moving an imported event is a sticky local override too; keep the
        // moved calendar and times across subsequent provider syncs.
        val imported = row[EventsTable.externalCalendarId] != null
        val now = clock.now()

        val zoneId = existing.timeZone ?: row[CalendarsTable.timeZone]
        val from = occurrenceStart ?: existing.start
        val recurrence = existing.recurrence
        val split = if (recurrence != null && from != existing.start) {
            recurrence.splitAt(existing.start, from, TimeZone.of(zoneId))
                ?: throw CalendarException.Invalid("from is not an occurrence of this event")
        } else {
            null
        }

        if (split?.truncated == null) {
            val etag = newEtag()
            EventsTable.update({ EventsTable.id eq id.toUuid() }) {
                it[calendarId] = destinationCalendarId.toUuid()
                it[EventsTable.etag] = etag
                it[updatedAt] = now
                if (imported) it[EventsTable.locallyModifiedAt] = now
            }
            return@dbQuery listOf(
                existing.copy(calendarId = destinationCalendarId, etag = etag, updatedAt = now),
            )
        }

        val truncatedRule = requireNotNull(split.truncated)
        val followingRule = split.following
        val truncatedEtag = newEtag()
        EventsTable.update({ EventsTable.id eq id.toUuid() }) {
            it[recurrenceCount] = truncatedRule.count
            it[recurrenceUntil] = truncatedRule.until
            it[EventsTable.etag] = truncatedEtag
            it[updatedAt] = now
            if (imported) it[EventsTable.locallyModifiedAt] = now
        }
        val truncated = existing.copy(recurrence = truncatedRule, etag = truncatedEtag, updatedAt = now)

        val newId = EventId.generate()
        val followingEtag = newEtag()
        val occurrenceEnd = from + (existing.end - existing.start)
        EventsTable.insert {
            it[EventsTable.id] = newId.toUuid()
            it[calendarId] = destinationCalendarId.toUuid()
            it[title] = existing.title
            it[description] = existing.description
            it[location] = existing.location
            it[url] = existing.url
            it[startAt] = from
            it[endAt] = occurrenceEnd
            it[allDay] = existing.allDay
            it[EventsTable.timeZone] = zoneId
            it[status] = existing.status.name
            it[recurrenceFrequency] = followingRule.frequency.name
            it[recurrenceInterval] = followingRule.interval
            it[recurrenceUntil] = followingRule.until
            it[recurrenceCount] = followingRule.count
            it[EventsTable.etag] = followingEtag
            it[openRsvp] = existing.openRsvp
            it[createdAt] = now
            it[updatedAt] = now
        }
        copyEventChildren(existing.id.toUuid(), newId.toUuid(), now)
        val following = existing.copy(
            id = newId,
            calendarId = destinationCalendarId,
            start = from,
            end = occurrenceEnd,
            timeZone = zoneId,
            recurrence = followingRule,
            etag = followingEtag,
            createdAt = now,
            updatedAt = now,
        )
        listOf(truncated, following)
    }

    override suspend fun deleteEvent(
        id: EventId,
        userId: UserId,
        expectedEtag: String?,
    ): Boolean = dbQuery {
        val row = (EventsTable innerJoin CalendarsTable)
            .selectAll()
            .where { EventsTable.id eq id.toUuid() }
            .singleOrNull()
            ?: return@dbQuery false
        val permission = permissionFor(row[EventsTable.calendarId], userId)
            ?: return@dbQuery false
        if (!permission.canWrite) throw CalendarException.Forbidden("read-only calendar")
        // Deletion differs from update/move: imported rows are still present
        // in the provider, so deleting one locally would only last until the
        // next sync resurrects it. Removing the import/connection is the
        // supported way to drop synced events.
        requireNotMirrored(row[EventsTable.calendarId])
        requireNotImported(row)
        val existing = row.toEvent()
        existing.requireEtag(expectedEtag)
        EventsTable.deleteWhere { EventsTable.id eq id.toUuid() } > 0
    }

    private fun JdbcTransaction.copyEventChildren(sourceId: Uuid, targetId: Uuid, now: Instant) {
        EventAttendeesTable.selectAll()
            .where { EventAttendeesTable.eventId eq sourceId }
            .forEach { attendee ->
                EventAttendeesTable.insert {
                    it[EventAttendeesTable.id] = Uuid.random()
                    it[eventId] = targetId
                    it[userId] = attendee[EventAttendeesTable.userId]
                    it[email] = attendee[EventAttendeesTable.email]
                    it[name] = attendee[EventAttendeesTable.name]
                    it[status] = attendee[EventAttendeesTable.status]
                    it[invitedBy] = attendee[EventAttendeesTable.invitedBy]
                    it[tokenHash] = null
                    it[createdAt] = attendee[EventAttendeesTable.createdAt]
                    it[respondedAt] = attendee[EventAttendeesTable.respondedAt]
                }
            }
        EventReminderSettingsTable.selectAll()
            .where { EventReminderSettingsTable.eventId eq sourceId }
            .forEach { setting ->
                EventReminderSettingsTable.insert {
                    it[userId] = setting[EventReminderSettingsTable.userId]
                    it[eventId] = targetId
                    it[useDefaults] = setting[EventReminderSettingsTable.useDefaults]
                    it[updatedAt] = now
                }
            }
        EventRemindersTable.selectAll()
            .where { EventRemindersTable.eventId eq sourceId }
            .forEach { reminder ->
                EventRemindersTable.insert {
                    it[userId] = reminder[EventRemindersTable.userId]
                    it[eventId] = targetId
                    it[offsetSeconds] = reminder[EventRemindersTable.offsetSeconds]
                    it[createdAt] = now
                }
            }
    }

    private fun JdbcTransaction.eventsFor(
        calendarId: Uuid,
        range: InstantRange?,
        fallbackZone: String,
        userId: UserId? = null,
    ): List<Event> {
        val query = EventsTable.selectAll().where {
            val calendarMatch = EventsTable.calendarId eq calendarId
            if (range == null) calendarMatch else calendarMatch and eventOverlaps(range)
        }
        val rows = query.orderBy(EventsTable.startAt to SortOrder.ASC, EventsTable.id to SortOrder.ASC)
            .toList()
        val statuses = userId?.let { rsvpStatuses(it, rows.map { row -> row[EventsTable.id] }) }.orEmpty()
        return rows.map { row -> row.toEvent(rsvpStatus = statuses[row[EventsTable.id]]?.wire) }
            .expanded(range) { event -> event.timeZone ?: fallbackZone }
    }

    private fun JdbcTransaction.rsvpStatuses(
        userId: UserId,
        eventIds: Collection<Uuid>,
    ): Map<Uuid, EventRsvpStatus> {
        if (eventIds.isEmpty()) return emptyMap()
        return EventAttendeesTable.selectAll()
            .where {
                (EventAttendeesTable.userId eq userId.toUuid()) and
                    (EventAttendeesTable.eventId inList eventIds.toList())
            }
            .associate { row ->
                val status = EventRsvpStatus.fromWire(row[EventAttendeesTable.status])
                    ?: EventRsvpStatus.INVITED
                row[EventAttendeesTable.eventId] to status
            }
    }

    private fun JdbcTransaction.requireNotMirrored(calendarId: Uuid) {
        val mirrored = ExternalCalendarsTable.selectAll()
            .where { ExternalCalendarsTable.calendarId eq calendarId }
            .count() > 0
        if (mirrored) {
            throw CalendarException.Forbidden("this calendar syncs from an external provider and is read-only")
        }
    }

    /**
     * Guards deletion of provider-backed events. Update and move are allowed
     * for writers (they set `locally_modified_at`), but a delete would be
     * undone by the next sync, so it remains rejected.
     */
    private fun requireNotImported(row: ResultRow) {
        if (row[EventsTable.externalCalendarId] != null) {
            throw CalendarException.Forbidden("this event is imported from an external provider and is read-only")
        }
    }

    private fun JdbcTransaction.permissionFor(calendarId: Uuid, userId: UserId): CalendarPermission? {
        val row = CalendarsTable.selectAll()
            .where { CalendarsTable.id eq calendarId }
            .singleOrNull()
            ?: return null
        if (row[CalendarsTable.ownerId] == userId.toUuid()) return CalendarPermission.OWNER
        val organizationPermission = row[CalendarsTable.organizationId]
            ?.let { organizationId -> orgRole(organizationId, userId) }
            ?.let(::organizationPermission)
        val teamPermission = teamPermissionFor(calendarId, userId)
        val sharePermission = CalendarSharesTable.selectAll()
            .where {
                (CalendarSharesTable.calendarId eq calendarId) and
                    (CalendarSharesTable.userId eq userId.toUuid())
            }
            .singleOrNull()
            ?.let { share ->
                CalendarPermission.fromWire(share[CalendarSharesTable.permission]) ?: CalendarPermission.READ
            }
        val followPermission = if (CalendarFollowersTable.selectAll()
                .where {
                    (CalendarFollowersTable.calendarId eq calendarId) and
                        (CalendarFollowersTable.userId eq userId.toUuid())
                }
                .count() > 0
        ) {
            CalendarPermission.FOLLOW
        } else {
            null
        }
        return listOfNotNull(organizationPermission, teamPermission, sharePermission, followPermission)
            .maxByOrNull { permissionRank(it) }
    }

    private fun JdbcTransaction.teamPermissionFor(calendarId: Uuid, userId: UserId): CalendarPermission? {
        val teamIds = OrganizationTeamMembersTable.selectAll()
            .where { OrganizationTeamMembersTable.userId eq userId.toUuid() }
            .map { it[OrganizationTeamMembersTable.teamId] }
        if (teamIds.isEmpty()) return null
        return CalendarTeamGrantsTable.selectAll()
            .where {
                (CalendarTeamGrantsTable.calendarId eq calendarId) and
                    (CalendarTeamGrantsTable.teamId inList teamIds)
            }
            .mapNotNull { row -> CalendarPermission.fromWire(row[CalendarTeamGrantsTable.permission]) }
            .maxByOrNull { permissionRank(it) }
    }

    private fun JdbcTransaction.teamCalendars(userId: UserId): Map<Uuid, CalendarPermission> {
        val teamIds = OrganizationTeamMembersTable.selectAll()
            .where { OrganizationTeamMembersTable.userId eq userId.toUuid() }
            .map { it[OrganizationTeamMembersTable.teamId] }
        if (teamIds.isEmpty()) return emptyMap()
        return CalendarTeamGrantsTable.selectAll()
            .where { CalendarTeamGrantsTable.teamId inList teamIds }
            .mapNotNull { row ->
                val permission = CalendarPermission.fromWire(row[CalendarTeamGrantsTable.permission])
                    ?: return@mapNotNull null
                row[CalendarTeamGrantsTable.calendarId] to permission
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, permissions) ->
                permissions.maxByOrNull { permissionRank(it) } ?: CalendarPermission.READ
            }
    }

    private fun JdbcTransaction.orgRole(organizationId: Uuid, userId: UserId): OrganizationRole? =
        OrganizationMembersTable.selectAll()
            .where {
                (OrganizationMembersTable.organizationId eq organizationId) and
                    (OrganizationMembersTable.userId eq userId.toUuid())
            }
            .singleOrNull()
            ?.get(OrganizationMembersTable.role)
            ?.let(OrganizationRole::fromWire)

    private fun JdbcTransaction.accessibleCalendar(id: CalendarId, userId: UserId): Calendar? {
        val permission = permissionFor(id.toUuid(), userId) ?: return null
        val hidden = hiddenIds(userId)
        return (CalendarsTable innerJoin UsersTable)
            .selectAll()
            .where { CalendarsTable.id eq id.toUuid() }
            .singleOrNull()
            ?.let { row ->
                row.toCalendar(
                    permission = permission,
                    hidden = id in hidden,
                    ownerScoped = permission == CalendarPermission.OWNER,
                    ownerAvatarVersion = row[UsersTable.avatarUpdatedAt]?.toEpochMilliseconds(),
                )
            }
    }

    private fun JdbcTransaction.accessibleCalendarIds(userId: UserId): List<Uuid> {
        val owned = CalendarsTable.selectAll()
            .where { CalendarsTable.ownerId eq userId.toUuid() }
            .map { it[CalendarsTable.id] }
        val organizationIds = organizationCalendars(userId).keys
        val shared = CalendarSharesTable.selectAll()
            .where { CalendarSharesTable.userId eq userId.toUuid() }
            .map { it[CalendarSharesTable.calendarId] }
        val followed = CalendarFollowersTable.selectAll()
            .where { CalendarFollowersTable.userId eq userId.toUuid() }
            .map { it[CalendarFollowersTable.calendarId] }
        return (owned + organizationIds + shared + followed).distinct()
    }

    private fun JdbcTransaction.organizationCalendars(userId: UserId): Map<Uuid, CalendarPermission> {
        val roles = OrganizationMembersTable.selectAll()
            .where { OrganizationMembersTable.userId eq userId.toUuid() }
            .associate { it[OrganizationMembersTable.organizationId] to it[OrganizationMembersTable.role] }
        val permissions = mutableMapOf<Uuid, CalendarPermission>()
        if (roles.isNotEmpty()) {
            CalendarsTable.selectAll()
                .where { CalendarsTable.organizationId inList roles.keys.toList() }
                .forEach { row ->
                    val organizationId = row[CalendarsTable.organizationId] ?: return@forEach
                    val role = roles[organizationId]?.let(OrganizationRole::fromWire) ?: return@forEach
                    val permission = organizationPermission(role) ?: return@forEach
                    val calendarId = row[CalendarsTable.id]
                    permissions[calendarId] = strongest(permissions[calendarId], permission)
                }
        }
        teamCalendars(userId).forEach { (calendarId, permission) ->
            permissions[calendarId] = strongest(permissions[calendarId], permission)
        }
        return permissions
    }

    private fun JdbcTransaction.manageableCalendar(id: CalendarId, actorId: UserId): Calendar? {
        val permission = permissionFor(id.toUuid(), actorId) ?: return null
        if (permission != CalendarPermission.OWNER) return null
        val hidden = hiddenIds(actorId)
        return CalendarsTable.selectAll()
            .where { CalendarsTable.id eq id.toUuid() }
            .singleOrNull()
            ?.toCalendar(hidden = id in hidden, ownerScoped = true)
    }

    private fun JdbcTransaction.manageableCalendarOp(id: CalendarId, actorId: UserId): Op<Boolean> {
        val actor = actorId.toUuid()
        val managedOrganizationIds = OrganizationMembersTable.selectAll()
            .where {
                (OrganizationMembersTable.userId eq actor) and
                    (OrganizationMembersTable.role inList organizationManagerRoles)
            }
            .map { it[OrganizationMembersTable.organizationId] }
        val owned = CalendarsTable.ownerId eq actor
        val byOrganization = if (managedOrganizationIds.isEmpty()) {
            Op.FALSE
        } else {
            CalendarsTable.organizationId inList managedOrganizationIds
        }
        return (CalendarsTable.id eq id.toUuid()) and (owned or byOrganization)
    }

    private fun JdbcTransaction.canManageTeam(
        organizationId: OrganizationId,
        teamId: OrganizationTeamId,
        actorId: UserId,
    ): Boolean {
        if (isOrganizationManager(orgRole(organizationId.toUuid(), actorId))) return true
        return OrganizationTeamMembersTable.selectAll()
            .where {
                (OrganizationTeamMembersTable.teamId eq teamId.toUuid()) and
                    (OrganizationTeamMembersTable.userId eq actorId.toUuid()) and
                    (OrganizationTeamMembersTable.role eq OrganizationTeamRole.MAINTAINER.wire)
            }
            .count() > 0
    }

    /**
     * Replaces every team grant with a write grant for the organization's
     * default `all` team so plain members keep read access. Organizations
     * normally have their `all` team created by the organization service; if it
     * is missing we recreate it, enroll the current organization members (as
     * [OrganizationTeamService.ensureDefaults] does), and then grant.
     */
    private fun JdbcTransaction.replaceWithDefaultTeamWrite(
        id: CalendarId,
        organizationId: OrganizationId,
        actorId: UserId,
    ) {
        CalendarTeamGrantsTable.deleteWhere { CalendarTeamGrantsTable.calendarId eq id.toUuid() }
        if (teams.grantAllTeamWriteInTransaction(this, id, organizationId)) return
        teams.ensureDefaultTeamInTransaction(this, organizationId, actorId)
        OrganizationMembersTable.selectAll()
            .where { OrganizationMembersTable.organizationId eq organizationId.toUuid() }
            .map { UserId(it[OrganizationMembersTable.userId].toString()) }
            .forEach { memberId ->
                teams.addUserToDefaultTeamInTransaction(this, organizationId, memberId)
            }
        check(teams.grantAllTeamWriteInTransaction(this, id, organizationId)) {
            "failed to grant the default team write access to calendar ${id.value}"
        }
    }

    private fun JdbcTransaction.hasExternalCalendar(calendarId: Uuid): Boolean =
        ExternalCalendarsTable.selectAll()
            .where { ExternalCalendarsTable.calendarId eq calendarId }
            .count() > 0

    private fun JdbcTransaction.shareRow(calendarId: CalendarId, userId: UserId): CalendarShare =
        (CalendarSharesTable innerJoin UsersTable)
            .selectAll()
            .where {
                (CalendarSharesTable.calendarId eq calendarId.toUuid()) and
                    (CalendarSharesTable.userId eq userId.toUuid())
            }
            .single()
            .toCalendarShare()

    private fun JdbcTransaction.hiddenIds(ownerId: UserId): Set<CalendarId> =
        CalendarHiddenTable.selectAll()
            .where { CalendarHiddenTable.userId eq ownerId.toUuid() }
            .map { CalendarId(it[CalendarHiddenTable.calendarId].toString()) }
            .toSet()

    private fun JdbcTransaction.prefs(ownerId: UserId): HolidayPrefs {
        val show = UsersTable.selectAll()
            .where { UsersTable.id eq ownerId.toUuid() }
            .single()[UsersTable.showHolidays]
        val subscribed = HolidaySubscriptionsTable.selectAll()
            .where { HolidaySubscriptionsTable.userId eq ownerId.toUuid() }
            .map { it[HolidaySubscriptionsTable.holidayId] }
            .sorted()
        val custom = CustomHolidaysTable.selectAll()
            .where { CustomHolidaysTable.userId eq ownerId.toUuid() }
            .orderBy(CustomHolidaysTable.title to SortOrder.ASC, CustomHolidaysTable.id to SortOrder.ASC)
            .map { row ->
                CustomHoliday(
                    id = row[CustomHolidaysTable.id].toString(),
                    title = row[CustomHolidaysTable.title],
                    month = row[CustomHolidaysTable.month],
                    day = row[CustomHolidaysTable.day],
                )
            }
        return HolidayPrefs(showHolidays = show, subscribedIds = subscribed, custom = custom)
    }

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }

    private fun newEtag(): String = Uuid.random().toString()
}

private fun Event.requireEtag(expectedEtag: String?) {
    if (expectedEtag == null || expectedEtag == "*") return
    if (etag != expectedEtag) {
        throw CalendarException.PreconditionFailed("etag mismatch")
    }
}

private fun organizationPermission(role: OrganizationRole): CalendarPermission? = when (role) {
    OrganizationRole.OWNER, OrganizationRole.ADMIN -> CalendarPermission.OWNER
    OrganizationRole.MEMBER -> null
}

private fun isOrganizationManager(role: OrganizationRole?): Boolean =
    role == OrganizationRole.OWNER || role == OrganizationRole.ADMIN

private fun strongest(current: CalendarPermission?, candidate: CalendarPermission): CalendarPermission =
    if (current == null || permissionRank(candidate) >= permissionRank(current)) candidate else current

private val organizationManagerRoles = listOf(OrganizationRole.OWNER.wire, OrganizationRole.ADMIN.wire)

private fun eventOverlaps(range: InstantRange): Op<Boolean> {
    val timed = EventsTable.recurrenceFrequency.isNull() and
        (EventsTable.startAt less range.end) and
        (EventsTable.endAt greater range.start)
    val recurring = EventsTable.recurrenceFrequency.isNotNull() and
        (EventsTable.startAt less range.end) and
        (EventsTable.recurrenceUntil.isNull() or (EventsTable.recurrenceUntil greater range.start))
    return timed or recurring
}

private fun List<Event>.expanded(range: InstantRange?, zoneId: (Event) -> String): List<Event> {
    if (range == null) return this
    return flatMap { event -> event.occurrencesIn(range, TimeZone.of(zoneId(event))) }
        .sortedWith(compareBy({ it.start }, { it.id.value }))
}

private fun CalendarId.toUuid(): Uuid = Uuid.parse(value)
private fun EventId.toUuid(): Uuid = Uuid.parse(value)
private fun UserId.toUuid(): Uuid = Uuid.parse(value)
private fun OrganizationId.toUuid(): Uuid = Uuid.parse(value)
private fun OrganizationTeamId.toUuid(): Uuid = Uuid.parse(value)

private fun ResultRow.toCalendar(
    permission: CalendarPermission = CalendarPermission.OWNER,
    hidden: Boolean = false,
    ownerScoped: Boolean = false,
    ownerAvatarVersion: Long? = null,
): Calendar = Calendar(
    id = CalendarId(this[CalendarsTable.id].toString()),
    ownerId = UserId(this[CalendarsTable.ownerId].toString()),
    displayName = this[CalendarsTable.displayName],
    description = this[CalendarsTable.description],
    timeZone = this[CalendarsTable.timeZone],
    color = this[CalendarsTable.color],
    hidden = hidden,
    permission = permission,
    publicLinkEnabled = ownerScoped && this[CalendarsTable.publicLinkEnabled],
    publicLinkToken = if (ownerScoped) this[CalendarsTable.publicLinkToken] else null,
    requestsEnabled = this[CalendarsTable.requestsEnabled],
    slotMinutes = this[CalendarsTable.slotMinutes],
    accessMode = PublicAccessMode.fromWire(this[CalendarsTable.accessMode]) ?: PublicAccessMode.INHERIT,
    createdAt = this[CalendarsTable.createdAt],
    updatedAt = this[CalendarsTable.updatedAt],
    ownerAvatarVersion = ownerAvatarVersion,
    organizationId = this[CalendarsTable.organizationId]?.let { OrganizationId(it.toString()) },
)

private fun ResultRow.toCalendarShare(): CalendarShare = CalendarShare(
    userId = UserId(this[CalendarSharesTable.userId].toString()),
    username = this[UsersTable.username],
    displayName = this[UsersTable.displayName],
    permission = CalendarPermission.fromWire(this[CalendarSharesTable.permission])
        ?: CalendarPermission.READ,
    createdAt = this[CalendarSharesTable.createdAt],
)

private fun ResultRow.toEvent(rsvpStatus: String? = null): Event {
    val frequency = this[EventsTable.recurrenceFrequency]
    val recurrence = frequency?.let {
        Recurrence(
            frequency = RecurrenceFrequency.valueOf(it),
            interval = this[EventsTable.recurrenceInterval] ?: 1,
            until = this[EventsTable.recurrenceUntil],
            count = this[EventsTable.recurrenceCount],
        )
    }
    return Event(
        id = EventId(this[EventsTable.id].toString()),
        calendarId = CalendarId(this[EventsTable.calendarId].toString()),
        title = this[EventsTable.title],
        description = this[EventsTable.description],
        location = this[EventsTable.location],
        url = this[EventsTable.url],
        start = this[EventsTable.startAt],
        end = this[EventsTable.endAt],
        allDay = this[EventsTable.allDay],
        timeZone = this[EventsTable.timeZone],
        status = EventStatus.valueOf(this[EventsTable.status]),
        recurrence = recurrence,
        etag = this[EventsTable.etag],
        createdAt = this[EventsTable.createdAt],
        updatedAt = this[EventsTable.updatedAt],
        openRsvp = this[EventsTable.openRsvp],
        rsvpStatus = rsvpStatus,
    )
}
