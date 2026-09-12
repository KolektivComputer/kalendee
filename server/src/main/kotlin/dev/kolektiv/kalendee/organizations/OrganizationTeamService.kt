package dev.kolektiv.kalendee.organizations

import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.auth.toUuid
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.OrganizationId
import dev.kolektiv.kalendee.calendar.OrganizationTeamId
import dev.kolektiv.kalendee.db.CalendarTeamGrantsTable
import dev.kolektiv.kalendee.db.CalendarsTable
import dev.kolektiv.kalendee.db.OrganizationMembersTable
import dev.kolektiv.kalendee.db.OrganizationTeamMembersTable
import dev.kolektiv.kalendee.db.OrganizationTeamsTable
import dev.kolektiv.kalendee.db.OrganizationsTable
import dev.kolektiv.kalendee.db.UsersTable
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

enum class OrganizationTeamRole(val wire: String) {
    MEMBER("member"),
    MAINTAINER("maintainer"),
    ;

    companion object {
        fun fromWire(raw: String): OrganizationTeamRole? =
            entries.firstOrNull { it.wire == raw.trim().lowercase() }

        fun parse(raw: String): OrganizationTeamRole =
            fromWire(raw) ?: throw CalendarException.Invalid("team role must be member or maintainer")
    }
}

data class OrganizationTeam(
    val id: OrganizationTeamId,
    val organizationId: OrganizationId,
    val slug: String,
    val name: String,
    val description: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class OrganizationTeamMember(
    val teamId: OrganizationTeamId,
    val organizationId: OrganizationId,
    val userId: UserId,
    val username: String,
    val displayName: String,
    val avatarVersion: Long?,
    val role: OrganizationTeamRole,
    val createdAt: Instant,
)

data class OrganizationTeamCalendarGrant(
    val teamId: OrganizationTeamId,
    val calendarId: CalendarId,
    val calendarDisplayName: String,
    val calendarColor: String,
    val permission: CalendarPermission,
    val createdAt: Instant,
)

data class OrganizationTeamLabel(
    val teamId: OrganizationTeamId,
    val teamName: String,
)

data class SubjectTeam(
    val team: OrganizationTeam,
    val memberCount: Int,
    val viewerRole: OrganizationTeamRole?,
    val canManageGrants: Boolean,
)

class OrganizationTeamService(
    private val database: Database,
    private val clock: Clock,
) {
    suspend fun create(
        actorId: UserId,
        orgId: OrganizationId,
        slug: String,
        name: String,
        description: String? = null,
    ): OrganizationTeam = dbQuery {
        val normalizedSlug = slug.trim().lowercase()
        requireSlug(normalizedSlug)
        if (normalizedSlug == DefaultTeamSlug) {
            throw CalendarException.Conflict("slug is reserved for the default team")
        }
        val cleanName = requireName(name)
        val cleanDescription = cleanDescription(description)
        requireOrgManager(
            orgRole(orgId, actorId)
                ?: throw CalendarException.Forbidden("not a member of this organization"),
        )
        if (teamBySlug(orgId, normalizedSlug) != null) {
            throw CalendarException.Conflict("team slug is already taken")
        }
        val now = clock.now()
        val id = OrganizationTeamId.generate()
        OrganizationTeamsTable.insert {
            it[OrganizationTeamsTable.id] = id.toUuid()
            it[organizationId] = orgId.toUuid()
            it[OrganizationTeamsTable.slug] = normalizedSlug
            it[OrganizationTeamsTable.name] = cleanName
            it[OrganizationTeamsTable.description] = cleanDescription
            it[createdAt] = now
            it[updatedAt] = now
        }
        OrganizationTeam(
            id = id,
            organizationId = orgId,
            slug = normalizedSlug,
            name = cleanName,
            description = cleanDescription,
            createdAt = now,
            updatedAt = now,
        )
    }

    suspend fun update(
        actorId: UserId,
        teamId: OrganizationTeamId,
        name: String? = null,
        description: String? = null,
    ): OrganizationTeam = dbQuery {
        val row = teamRow(teamId) ?: throw CalendarException.NotFound("team not found")
        requireOrgManager(
            orgRole(row.organizationId(), actorId)
                ?: throw CalendarException.Forbidden("not a member of this organization"),
        )
        val cleanName = name?.let(::requireName)
        val cleanDescription = description?.let(::cleanDescription)
        val now = clock.now()
        val updated = row.toTeam().copy(
            name = cleanName ?: row[OrganizationTeamsTable.name],
            description = if (description != null) {
                cleanDescription
            } else {
                row[OrganizationTeamsTable.description]
            },
            updatedAt = now,
        )
        OrganizationTeamsTable.update({ OrganizationTeamsTable.id eq teamId.toUuid() }) {
            it[OrganizationTeamsTable.name] = updated.name
            it[OrganizationTeamsTable.description] = updated.description
            it[updatedAt] = now
        }
        updated
    }

    suspend fun delete(actorId: UserId, teamId: OrganizationTeamId): Boolean = dbQuery {
        val row = teamRow(teamId) ?: throw CalendarException.NotFound("team not found")
        requireOrgManager(
            orgRole(row.organizationId(), actorId)
                ?: throw CalendarException.Forbidden("not a member of this organization"),
        )
        OrganizationTeamsTable.deleteWhere { OrganizationTeamsTable.id eq teamId.toUuid() } > 0
    }

    suspend fun teamsFor(actorId: UserId, orgId: OrganizationId): List<OrganizationTeam> = dbQuery {
        val actorRole = orgRole(orgId, actorId)
            ?: throw CalendarException.Forbidden("not a member of this organization")
        if (actorRole == OrganizationRole.MEMBER) {
            val teamIds = teamIdsFor(actorId)
            if (teamIds.isEmpty()) {
                emptyList()
            } else {
                OrganizationTeamsTable.selectAll()
                    .where {
                        (OrganizationTeamsTable.organizationId eq orgId.toUuid()) and
                            (OrganizationTeamsTable.id inList teamIds)
                    }
                    .orderBy(
                        OrganizationTeamsTable.name to SortOrder.ASC,
                        OrganizationTeamsTable.id to SortOrder.ASC,
                    )
                    .map { it.toTeam() }
            }
        } else {
            OrganizationTeamsTable.selectAll()
                .where { OrganizationTeamsTable.organizationId eq orgId.toUuid() }
                .orderBy(
                    OrganizationTeamsTable.name to SortOrder.ASC,
                    OrganizationTeamsTable.id to SortOrder.ASC,
                )
                .map { it.toTeam() }
        }
    }

    suspend fun team(actorId: UserId, teamId: OrganizationTeamId): OrganizationTeam? = dbQuery {
        val row = teamRow(teamId) ?: return@dbQuery null
        val orgId = row.organizationId()
        val actorRole = orgRole(orgId, actorId)
            ?: throw CalendarException.Forbidden("not a member of this organization")
        if (actorRole == OrganizationRole.MEMBER && teamMemberRow(teamId, actorId) == null) {
            return@dbQuery null
        }
        row.toTeam()
    }

    suspend fun members(actorId: UserId, teamId: OrganizationTeamId): List<OrganizationTeamMember> = dbQuery {
        val row = visibleTeam(actorId, teamId)
        val orgId = row.organizationId()
        (OrganizationTeamMembersTable innerJoin UsersTable)
            .selectAll()
            .where { OrganizationTeamMembersTable.teamId eq teamId.toUuid() }
            .orderBy(UsersTable.username to SortOrder.ASC)
            .map { it.toTeamMember(teamId, orgId) }
    }

    suspend fun grants(actorId: UserId, teamId: OrganizationTeamId): List<OrganizationTeamCalendarGrant> = dbQuery {
        visibleTeam(actorId, teamId)
        (CalendarTeamGrantsTable innerJoin CalendarsTable)
            .selectAll()
            .where { CalendarTeamGrantsTable.teamId eq teamId.toUuid() }
            .orderBy(CalendarsTable.displayName to SortOrder.ASC, CalendarsTable.id to SortOrder.ASC)
            .map { it.toGrant(teamId) }
    }

    suspend fun addMember(
        actorId: UserId,
        teamId: OrganizationTeamId,
        userId: UserId,
    ): OrganizationTeamMember = dbQuery {
        val row = managedTeam(actorId, teamId)
        val orgId = row.organizationId()
        if (orgRole(orgId, userId) == null) {
            throw CalendarException.Invalid("user is not a member of this organization")
        }
        if (teamMemberRow(teamId, userId) != null) {
            throw CalendarException.Conflict("user is already a team member")
        }
        val target = userRow(userId) ?: throw CalendarException.NotFound("user not found")
        val now = clock.now()
        OrganizationTeamMembersTable.insert {
            it[OrganizationTeamMembersTable.teamId] = teamId.toUuid()
            it[OrganizationTeamMembersTable.userId] = userId.toUuid()
            it[role] = OrganizationTeamRole.MEMBER.wire
            it[createdAt] = now
        }
        OrganizationTeamMember(
            teamId = teamId,
            organizationId = orgId,
            userId = userId,
            username = target[UsersTable.username],
            displayName = target[UsersTable.displayName],
            avatarVersion = target[UsersTable.avatarUpdatedAt]?.toEpochMilliseconds(),
            role = OrganizationTeamRole.MEMBER,
            createdAt = now,
        )
    }

    suspend fun removeMember(actorId: UserId, teamId: OrganizationTeamId, userId: UserId): Boolean = dbQuery {
        managedTeam(actorId, teamId)
        OrganizationTeamMembersTable.deleteWhere {
            (OrganizationTeamMembersTable.teamId eq teamId.toUuid()) and
                (OrganizationTeamMembersTable.userId eq userId.toUuid())
        } > 0
    }

    suspend fun setMemberRole(
        actorId: UserId,
        teamId: OrganizationTeamId,
        userId: UserId,
        role: OrganizationTeamRole,
    ): OrganizationTeamMember = dbQuery {
        val row = managedTeam(actorId, teamId)
        teamMemberRow(teamId, userId) ?: throw CalendarException.NotFound("team member not found")
        OrganizationTeamMembersTable.update({
            (OrganizationTeamMembersTable.teamId eq teamId.toUuid()) and
                (OrganizationTeamMembersTable.userId eq userId.toUuid())
        }) {
            it[OrganizationTeamMembersTable.role] = role.wire
        }
        (OrganizationTeamMembersTable innerJoin UsersTable)
            .selectAll()
            .where {
                (OrganizationTeamMembersTable.teamId eq teamId.toUuid()) and
                    (OrganizationTeamMembersTable.userId eq userId.toUuid())
            }
            .single()
            .toTeamMember(teamId, row.organizationId())
    }

    suspend fun grant(
        actorId: UserId,
        calendarId: CalendarId,
        teamId: OrganizationTeamId,
        permission: CalendarPermission,
    ): OrganizationTeamCalendarGrant = dbQuery {
        if (permission != CalendarPermission.READ && permission != CalendarPermission.WRITE) {
            throw CalendarException.Invalid("permission must be read or write")
        }
        val row = managedTeam(actorId, teamId)
        val orgId = row.organizationId()
        val calendar = calendarRow(calendarId) ?: throw CalendarException.NotFound("calendar not found")
        requireSameOrganization(orgId, calendar)
        requireCalendarManager(orgId, calendar, actorId)
        upsertGrant(calendarId.toUuid(), teamId, permission, clock.now())
        grantRow(calendarId, teamId) ?: throw CalendarException.NotFound("calendar grant not found")
    }

    suspend fun revoke(actorId: UserId, calendarId: CalendarId, teamId: OrganizationTeamId): Boolean = dbQuery {
        val row = managedTeam(actorId, teamId)
        val orgId = row.organizationId()
        val calendar = calendarRow(calendarId) ?: throw CalendarException.NotFound("calendar not found")
        requireSameOrganization(orgId, calendar)
        requireCalendarManager(orgId, calendar, actorId)
        CalendarTeamGrantsTable.deleteWhere {
            (CalendarTeamGrantsTable.calendarId eq calendarId.toUuid()) and
                (CalendarTeamGrantsTable.teamId eq teamId.toUuid())
        } > 0
    }

    suspend fun teamCalendarsFor(userId: UserId): Map<Uuid, CalendarPermission> = dbQuery {
        teamCalendars(userId)
    }

    /**
     * Every team the user can see: their teams as a member, plus every team of
     * the organizations they own or administer, with the viewer's team role.
     */
    suspend fun teamsForSubject(userId: UserId): List<SubjectTeam> = dbQuery {
        val roles = organizationRoles(userId)
        val memberships = teamMemberships(userId)
        val rows = visibleTeamRows(roles, memberships.keys)
        if (rows.isEmpty()) return@dbQuery emptyList()
        val teams = rows.map { it.toTeam() }
        val counts = teamMemberCounts(teams.map { it.id })
        teams.map { team ->
            val viewerRole = memberships[team.id]
            SubjectTeam(
                team = team,
                memberCount = counts[team.id] ?: 0,
                viewerRole = viewerRole,
                canManageGrants = canManageTeam(roles[team.organizationId], viewerRole),
            )
        }
    }

    /**
     * The primary visible team for every calendar the subject can reach through
     * a team grant. A named team beats the default `all` team so explicit
     * grants stay visible in the sidebar; then the strongest permission wins,
     * then the team name, so the label is stable.
     */
    suspend fun teamLabelsFor(userId: UserId): Map<CalendarId, OrganizationTeamLabel> = dbQuery {
        val roles = organizationRoles(userId)
        val memberships = teamMemberships(userId)
        val teams = visibleTeamRows(roles, memberships.keys).map { it.toTeam() }
        if (teams.isEmpty()) return@dbQuery emptyMap()
        val teamsById = teams.associateBy { it.id }
        val best = mutableMapOf<CalendarId, GrantCandidate>()
        CalendarTeamGrantsTable.selectAll()
            .where { CalendarTeamGrantsTable.teamId inList teams.map { it.id.toUuid() } }
            .forEach { row ->
                val team = teamsById[OrganizationTeamId(row[CalendarTeamGrantsTable.teamId].toString())]
                    ?: return@forEach
                val permission = CalendarPermission.fromWire(row[CalendarTeamGrantsTable.permission])
                    ?: return@forEach
                val calendarId = CalendarId(row[CalendarTeamGrantsTable.calendarId].toString())
                val candidate = GrantCandidate(permission, team)
                val current = best[calendarId]
                if (current == null || candidate.outranks(current)) best[calendarId] = candidate
            }
        best.mapValues { (_, candidate) ->
            OrganizationTeamLabel(teamId = candidate.team.id, teamName = candidate.team.name)
        }
    }

    suspend fun defaultTeamId(orgId: OrganizationId): OrganizationTeamId? = dbQuery {
        defaultTeamRow(orgId)?.get(OrganizationTeamsTable.id)?.let { OrganizationTeamId(it.toString()) }
    }

    suspend fun ensureDefaultTeam(orgId: OrganizationId, ownerId: UserId): OrganizationTeamId = dbQuery {
        ensureDefaultTeamInTransaction(this, orgId, ownerId)
    }

    suspend fun addUserToDefaultTeam(orgId: OrganizationId, userId: UserId): Boolean = dbQuery {
        addUserToDefaultTeamInTransaction(this, orgId, userId)
    }

    suspend fun removeUserFromOrganization(orgId: OrganizationId, userId: UserId): Int = dbQuery {
        removeUserFromOrganizationInTransaction(this, orgId, userId)
    }

    suspend fun grantAllTeamWrite(calendarId: CalendarId, orgId: OrganizationId): Boolean = dbQuery {
        grantAllTeamWriteInTransaction(this, calendarId, orgId)
    }

    /**
     * Creates the `all` team for every organization that is missing one, adds
     * every current member and grants write access to every calendar of the
     * organization. Returns the number of organizations that were backfilled.
     */
    suspend fun ensureDefaults(): Int = dbQuery {
        val withDefaultTeam = OrganizationTeamsTable.selectAll()
            .where { OrganizationTeamsTable.slug eq DefaultTeamSlug }
            .map { it[OrganizationTeamsTable.organizationId] }
            .toSet()
        val orgIds = OrganizationsTable.selectAll()
            .orderBy(OrganizationsTable.createdAt to SortOrder.ASC, OrganizationsTable.id to SortOrder.ASC)
            .map { it[OrganizationsTable.id] }
            .filter { it !in withDefaultTeam }
        var backfilled = 0
        for (orgUuid in orgIds) {
            val orgId = OrganizationId(orgUuid.toString())
            val memberIds = OrganizationMembersTable.selectAll()
                .where { OrganizationMembersTable.organizationId eq orgUuid }
                .orderBy(OrganizationMembersTable.createdAt to SortOrder.ASC)
                .map { it[OrganizationMembersTable.userId] }
            val teamId = insertDefaultTeam(orgId)
            memberIds.forEach { memberId ->
                insertTeamMemberIfAbsent(teamId, UserId(memberId.toString()))
            }
            CalendarsTable.selectAll()
                .where { CalendarsTable.organizationId eq orgUuid }
                .map { it[CalendarsTable.id] }
                .forEach { calendarId ->
                    upsertGrant(calendarId, teamId, CalendarPermission.WRITE, clock.now())
                }
            backfilled++
        }
        backfilled
    }

    internal fun ensureDefaultTeamInTransaction(
        tx: JdbcTransaction,
        orgId: OrganizationId,
        ownerId: UserId,
    ): OrganizationTeamId {
        val teamId = tx.defaultTeamRow(orgId)?.get(OrganizationTeamsTable.id)
            ?.let { OrganizationTeamId(it.toString()) }
            ?: tx.insertDefaultTeam(orgId)
        tx.insertTeamMemberIfAbsent(teamId, ownerId)
        return teamId
    }

    internal fun addUserToDefaultTeamInTransaction(
        tx: JdbcTransaction,
        orgId: OrganizationId,
        userId: UserId,
    ): Boolean {
        val teamId = tx.defaultTeamRow(orgId)?.get(OrganizationTeamsTable.id)
            ?.let { OrganizationTeamId(it.toString()) }
            ?: tx.insertDefaultTeam(orgId)
        return tx.insertTeamMemberIfAbsent(teamId, userId)
    }

    internal fun removeUserFromOrganizationInTransaction(
        tx: JdbcTransaction,
        orgId: OrganizationId,
        userId: UserId,
    ): Int {
        val teamIds = tx.teamIdsForOrganization(orgId)
        if (teamIds.isEmpty()) return 0
        return tx.run {
            OrganizationTeamMembersTable.deleteWhere {
                (OrganizationTeamMembersTable.userId eq userId.toUuid()) and
                    (OrganizationTeamMembersTable.teamId inList teamIds)
            }
        }
    }

    /**
     * Deletes every team of an organization together with its memberships and
     * calendar grants. [OrganizationService.delete] calls this before deleting
     * the organization itself because H2 cannot process the two cascade paths
     * that reach `calendar_team_grants` from `organizations` (through teams and
     * through calendars) in a single statement.
     */
    internal fun removeTeamsForOrganizationInTransaction(tx: JdbcTransaction, orgId: OrganizationId): Int =
        tx.run {
            OrganizationTeamsTable.deleteWhere { OrganizationTeamsTable.organizationId eq orgId.toUuid() }
        }

    internal fun grantAllTeamWriteInTransaction(
        tx: JdbcTransaction,
        calendarId: CalendarId,
        orgId: OrganizationId,
    ): Boolean {
        val teamId = tx.defaultTeamRow(orgId)?.get(OrganizationTeamsTable.id)
            ?.let { OrganizationTeamId(it.toString()) }
            ?: return false
        tx.upsertGrant(calendarId.toUuid(), teamId, CalendarPermission.WRITE, clock.now())
        return true
    }

    private fun JdbcTransaction.teamCalendars(userId: UserId): Map<Uuid, CalendarPermission> {
        val teamIds = teamIdsFor(userId)
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

    private fun JdbcTransaction.visibleTeam(actorId: UserId, teamId: OrganizationTeamId): ResultRow {
        val row = teamRow(teamId) ?: throw CalendarException.NotFound("team not found")
        val actorRole = orgRole(row.organizationId(), actorId)
            ?: throw CalendarException.Forbidden("not a member of this organization")
        if (actorRole == OrganizationRole.MEMBER && teamMemberRow(teamId, actorId) == null) {
            throw CalendarException.Forbidden("not a member of this team")
        }
        return row
    }

    private fun JdbcTransaction.managedTeam(actorId: UserId, teamId: OrganizationTeamId): ResultRow {
        val row = teamRow(teamId) ?: throw CalendarException.NotFound("team not found")
        val orgId = row.organizationId()
        val actorRole = orgRole(orgId, actorId)
            ?: throw CalendarException.Forbidden("not a member of this organization")
        if (actorRole == OrganizationRole.OWNER || actorRole == OrganizationRole.ADMIN) {
            return row
        }
        val membership = teamMemberRow(teamId, actorId)
        val teamRole = membership?.get(OrganizationTeamMembersTable.role)?.let(OrganizationTeamRole::fromWire)
        if (teamRole != OrganizationTeamRole.MAINTAINER) {
            throw CalendarException.Forbidden("only owners, admins, and team maintainers can manage a team")
        }
        return row
    }

    private fun JdbcTransaction.requireCalendarManager(
        orgId: OrganizationId,
        calendar: ResultRow,
        actorId: UserId,
    ) {
        if (calendar[CalendarsTable.ownerId] == actorId.toUuid()) return
        val actorRole = orgRole(orgId, actorId)
        if (actorRole == OrganizationRole.OWNER || actorRole == OrganizationRole.ADMIN) return
        throw CalendarException.Forbidden("only calendar owners can manage team grants")
    }

    private fun requireSameOrganization(orgId: OrganizationId, calendar: ResultRow) {
        if (calendar[CalendarsTable.organizationId] != orgId.toUuid()) {
            throw CalendarException.Invalid("team and calendar must belong to the same organization")
        }
    }

    private fun JdbcTransaction.upsertGrant(
        calendarId: Uuid,
        teamId: OrganizationTeamId,
        permission: CalendarPermission,
        now: Instant,
    ) {
        val exists = CalendarTeamGrantsTable.selectAll()
            .where {
                (CalendarTeamGrantsTable.calendarId eq calendarId) and
                    (CalendarTeamGrantsTable.teamId eq teamId.toUuid())
            }
            .count() > 0
        if (exists) {
            CalendarTeamGrantsTable.update({
                (CalendarTeamGrantsTable.calendarId eq calendarId) and
                    (CalendarTeamGrantsTable.teamId eq teamId.toUuid())
            }) {
                it[CalendarTeamGrantsTable.permission] = permission.wire
            }
        } else {
            CalendarTeamGrantsTable.insert {
                it[CalendarTeamGrantsTable.calendarId] = calendarId
                it[CalendarTeamGrantsTable.teamId] = teamId.toUuid()
                it[CalendarTeamGrantsTable.permission] = permission.wire
                it[createdAt] = now
            }
        }
    }

    private fun JdbcTransaction.insertDefaultTeam(orgId: OrganizationId): OrganizationTeamId {
        val now = clock.now()
        val id = OrganizationTeamId.generate()
        OrganizationTeamsTable.insert {
            it[OrganizationTeamsTable.id] = id.toUuid()
            it[organizationId] = orgId.toUuid()
            it[slug] = DefaultTeamSlug
            it[name] = DefaultTeamName
            it[description] = null
            it[createdAt] = now
            it[updatedAt] = now
        }
        return id
    }

    private fun JdbcTransaction.insertTeamMemberIfAbsent(teamId: OrganizationTeamId, userId: UserId): Boolean {
        if (teamMemberRow(teamId, userId) != null) return false
        OrganizationTeamMembersTable.insert {
            it[OrganizationTeamMembersTable.teamId] = teamId.toUuid()
            it[OrganizationTeamMembersTable.userId] = userId.toUuid()
            it[role] = OrganizationTeamRole.MEMBER.wire
            it[createdAt] = clock.now()
        }
        return true
    }

    private fun JdbcTransaction.teamBySlug(orgId: OrganizationId, slug: String): ResultRow? =
        OrganizationTeamsTable.selectAll()
            .where {
                (OrganizationTeamsTable.organizationId eq orgId.toUuid()) and
                    (OrganizationTeamsTable.slug eq slug)
            }
            .singleOrNull()

    private fun JdbcTransaction.defaultTeamRow(orgId: OrganizationId): ResultRow? =
        teamBySlug(orgId, DefaultTeamSlug)

    private fun JdbcTransaction.teamRow(teamId: OrganizationTeamId): ResultRow? =
        OrganizationTeamsTable.selectAll()
            .where { OrganizationTeamsTable.id eq teamId.toUuid() }
            .singleOrNull()

    private fun JdbcTransaction.teamMemberRow(teamId: OrganizationTeamId, userId: UserId): ResultRow? =
        OrganizationTeamMembersTable.selectAll()
            .where {
                (OrganizationTeamMembersTable.teamId eq teamId.toUuid()) and
                    (OrganizationTeamMembersTable.userId eq userId.toUuid())
            }
            .singleOrNull()

    private fun JdbcTransaction.userRow(userId: UserId): ResultRow? =
        UsersTable.selectAll()
            .where { UsersTable.id eq userId.toUuid() }
            .singleOrNull()

    private fun JdbcTransaction.calendarRow(calendarId: CalendarId): ResultRow? =
        CalendarsTable.selectAll()
            .where { CalendarsTable.id eq calendarId.toUuid() }
            .singleOrNull()

    private fun JdbcTransaction.grantRow(
        calendarId: CalendarId,
        teamId: OrganizationTeamId,
    ): OrganizationTeamCalendarGrant? =
        (CalendarTeamGrantsTable innerJoin CalendarsTable)
            .selectAll()
            .where {
                (CalendarTeamGrantsTable.calendarId eq calendarId.toUuid()) and
                    (CalendarTeamGrantsTable.teamId eq teamId.toUuid())
            }
            .singleOrNull()
            ?.toGrant(teamId)

    private fun JdbcTransaction.orgRole(orgId: OrganizationId, userId: UserId): OrganizationRole? =
        OrganizationMembersTable.selectAll()
            .where {
                (OrganizationMembersTable.organizationId eq orgId.toUuid()) and
                    (OrganizationMembersTable.userId eq userId.toUuid())
            }
            .singleOrNull()
            ?.get(OrganizationMembersTable.role)
            ?.let(OrganizationRole::fromWire)

    private fun JdbcTransaction.teamIdsFor(userId: UserId): List<Uuid> =
        OrganizationTeamMembersTable.selectAll()
            .where { OrganizationTeamMembersTable.userId eq userId.toUuid() }
            .map { it[OrganizationTeamMembersTable.teamId] }

    private fun JdbcTransaction.teamIdsForOrganization(orgId: OrganizationId): List<Uuid> =
        OrganizationTeamsTable.selectAll()
            .where { OrganizationTeamsTable.organizationId eq orgId.toUuid() }
            .map { it[OrganizationTeamsTable.id] }

    private fun JdbcTransaction.organizationRoles(userId: UserId): Map<OrganizationId, OrganizationRole> =
        OrganizationMembersTable.selectAll()
            .where { OrganizationMembersTable.userId eq userId.toUuid() }
            .associate { row ->
                OrganizationId(row[OrganizationMembersTable.organizationId].toString()) to
                    (OrganizationRole.fromWire(row[OrganizationMembersTable.role]) ?: OrganizationRole.MEMBER)
            }

    private fun JdbcTransaction.teamMemberships(userId: UserId): Map<OrganizationTeamId, OrganizationTeamRole> =
        OrganizationTeamMembersTable.selectAll()
            .where { OrganizationTeamMembersTable.userId eq userId.toUuid() }
            .associate { row ->
                OrganizationTeamId(row[OrganizationTeamMembersTable.teamId].toString()) to
                    (OrganizationTeamRole.fromWire(row[OrganizationTeamMembersTable.role])
                        ?: OrganizationTeamRole.MEMBER)
            }

    private fun JdbcTransaction.visibleTeamRows(
        roles: Map<OrganizationId, OrganizationRole>,
        memberTeamIds: Collection<OrganizationTeamId>,
    ): List<ResultRow> {
        val managerOrgIds = roles.filterValues { it != OrganizationRole.MEMBER }.keys.map { it.toUuid() }
        val teamIds = memberTeamIds.map { it.toUuid() }
        if (managerOrgIds.isEmpty() && teamIds.isEmpty()) return emptyList()
        val where = when {
            teamIds.isEmpty() -> OrganizationTeamsTable.organizationId inList managerOrgIds
            managerOrgIds.isEmpty() -> OrganizationTeamsTable.id inList teamIds
            else ->
                (OrganizationTeamsTable.id inList teamIds) or
                    (OrganizationTeamsTable.organizationId inList managerOrgIds)
        }
        return OrganizationTeamsTable.selectAll()
            .where { where }
            .orderBy(
                OrganizationTeamsTable.name to SortOrder.ASC,
                OrganizationTeamsTable.id to SortOrder.ASC,
            )
            .toList()
    }

    private fun JdbcTransaction.teamMemberCounts(teamIds: List<OrganizationTeamId>): Map<OrganizationTeamId, Int> {
        if (teamIds.isEmpty()) return emptyMap()
        return OrganizationTeamMembersTable.selectAll()
            .where { OrganizationTeamMembersTable.teamId inList teamIds.map { it.toUuid() } }
            .map { OrganizationTeamId(it[OrganizationTeamMembersTable.teamId].toString()) }
            .groupingBy { it }
            .eachCount()
    }

    private fun canManageTeam(orgRole: OrganizationRole?, teamRole: OrganizationTeamRole?): Boolean =
        orgRole == OrganizationRole.OWNER ||
            orgRole == OrganizationRole.ADMIN ||
            teamRole == OrganizationTeamRole.MAINTAINER

    private data class GrantCandidate(
        val permission: CalendarPermission,
        val team: OrganizationTeam,
    ) {
        fun outranks(other: GrantCandidate): Boolean {
            val isDefault = team.slug == DefaultTeamSlug
            val otherDefault = other.team.slug == DefaultTeamSlug
            if (isDefault != otherDefault) return otherDefault
            val rank = permissionRank(permission)
            val otherRank = permissionRank(other.permission)
            if (rank != otherRank) return rank > otherRank
            val name = team.name.lowercase()
            val otherName = other.team.name.lowercase()
            if (name != otherName) return name < otherName
            return team.id.value < other.team.id.value
        }
    }

    private fun requireOrgManager(role: OrganizationRole) {
        if (role != OrganizationRole.OWNER && role != OrganizationRole.ADMIN) {
            throw CalendarException.Forbidden("only owners and admins can manage teams")
        }
    }

    private fun requireSlug(slug: String) {
        if (!SlugPattern.matches(slug)) {
            throw CalendarException.Invalid(
                "slug must be 3-32 characters, start with a letter or digit, and contain only " +
                    "lowercase letters, digits, dots, underscores, or hyphens",
            )
        }
    }

    private fun requireName(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            throw CalendarException.Invalid("team name must not be blank")
        }
        if (trimmed.length > MaxNameLength) {
            throw CalendarException.Invalid("team name must be at most $MaxNameLength characters")
        }
        return trimmed
    }

    private fun cleanDescription(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.length > MaxDescriptionLength) {
            throw CalendarException.Invalid("description must be at most $MaxDescriptionLength characters")
        }
        return trimmed
    }

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }

    private companion object {
        private const val MaxNameLength = 80
        private const val MaxDescriptionLength = 500
        private val SlugPattern = Regex("^[a-z0-9][a-z0-9._-]{2,31}$")
    }
}

internal const val DefaultTeamSlug = "all"
private const val DefaultTeamName = "Everyone"

internal fun permissionRank(permission: CalendarPermission): Int = when (permission) {
    CalendarPermission.OWNER -> 3
    CalendarPermission.WRITE -> 2
    CalendarPermission.READ -> 1
    CalendarPermission.FOLLOW -> 0
}

private fun CalendarId.toUuid(): Uuid = Uuid.parse(value)
private fun OrganizationTeamId.toUuid(): Uuid = Uuid.parse(value)

private fun ResultRow.organizationId(): OrganizationId =
    OrganizationId(this[OrganizationTeamsTable.organizationId].toString())

private fun ResultRow.toTeam(): OrganizationTeam = OrganizationTeam(
    id = OrganizationTeamId(this[OrganizationTeamsTable.id].toString()),
    organizationId = OrganizationId(this[OrganizationTeamsTable.organizationId].toString()),
    slug = this[OrganizationTeamsTable.slug],
    name = this[OrganizationTeamsTable.name],
    description = this[OrganizationTeamsTable.description],
    createdAt = this[OrganizationTeamsTable.createdAt],
    updatedAt = this[OrganizationTeamsTable.updatedAt],
)

private fun ResultRow.toTeamMember(
    teamId: OrganizationTeamId,
    organizationId: OrganizationId,
): OrganizationTeamMember = OrganizationTeamMember(
    teamId = teamId,
    organizationId = organizationId,
    userId = UserId(this[OrganizationTeamMembersTable.userId].toString()),
    username = this[UsersTable.username],
    displayName = this[UsersTable.displayName],
    avatarVersion = this[UsersTable.avatarUpdatedAt]?.toEpochMilliseconds(),
    role = OrganizationTeamRole.fromWire(this[OrganizationTeamMembersTable.role]) ?: OrganizationTeamRole.MEMBER,
    createdAt = this[OrganizationTeamMembersTable.createdAt],
)

private fun ResultRow.toGrant(teamId: OrganizationTeamId): OrganizationTeamCalendarGrant =
    OrganizationTeamCalendarGrant(
        teamId = teamId,
        calendarId = CalendarId(this[CalendarsTable.id].toString()),
        calendarDisplayName = this[CalendarsTable.displayName],
        calendarColor = this[CalendarsTable.color],
        permission = CalendarPermission.fromWire(this[CalendarTeamGrantsTable.permission])
            ?: CalendarPermission.READ,
        createdAt = this[CalendarTeamGrantsTable.createdAt],
    )
