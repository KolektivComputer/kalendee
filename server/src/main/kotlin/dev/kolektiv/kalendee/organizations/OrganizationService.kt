package dev.kolektiv.kalendee.organizations

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.SessionTokens
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.auth.requireDisplayName
import dev.kolektiv.kalendee.auth.requireEmail
import dev.kolektiv.kalendee.auth.toUuid
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.OrganizationId
import dev.kolektiv.kalendee.calendar.OrganizationVisibility
import dev.kolektiv.kalendee.db.OrganizationInvitationsTable
import dev.kolektiv.kalendee.db.OrganizationMembersTable
import dev.kolektiv.kalendee.db.OrganizationsTable
import dev.kolektiv.kalendee.db.UsersTable
import dev.kolektiv.kalendee.mail.MailService
import dev.kolektiv.kalendee.notifications.NotificationService
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

enum class OrganizationRole(val wire: String) {
    OWNER("owner"),
    ADMIN("admin"),
    MEMBER("member"),
    ;

    companion object {
        fun fromWire(raw: String): OrganizationRole? =
            entries.firstOrNull { it.wire == raw.trim().lowercase() }

        fun parse(raw: String): OrganizationRole =
            fromWire(raw) ?: throw CalendarException.Invalid("role must be owner, admin, or member")
    }
}

data class Organization(
    val id: OrganizationId,
    val slug: String,
    val displayName: String,
    val description: String?,
    val avatarKey: String?,
    val avatarUpdatedAt: Instant?,
    val visibility: OrganizationVisibility,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class OrganizationMembership(
    val organization: Organization,
    val role: OrganizationRole,
)

data class OrganizationMember(
    val organizationId: OrganizationId,
    val userId: UserId,
    val username: String,
    val displayName: String,
    val avatarVersion: Long?,
    val role: OrganizationRole,
    val createdAt: Instant,
)

data class OrganizationInvitation(
    val id: Uuid,
    val organizationId: OrganizationId,
    val inviterId: UserId?,
    val inviteeUserId: UserId?,
    val email: String?,
    val role: OrganizationRole,
    val status: String,
    val expiresAt: Instant,
    val createdAt: Instant,
    val respondedAt: Instant?,
) {
    companion object {
        const val PENDING = "pending"
        const val ACCEPTED = "accepted"
        const val DECLINED = "declined"
        const val REVOKED = "revoked"
    }
}

data class OrganizationInvitationResult(
    val invitationId: Uuid,
    val status: String,
)

class OrganizationService(
    private val database: Database,
    private val auth: AuthService,
    private val notifications: NotificationService,
    private val mail: MailService,
    private val clock: Clock,
) {
    suspend fun create(
        actorId: UserId,
        slug: String,
        displayName: String,
        description: String? = null,
    ): Organization {
        val normalizedSlug = slug.trim().lowercase()
        requireSlug(normalizedSlug)
        val name = requireDisplayName(displayName)
        val cleanDescription = cleanDescription(description)
        return dbQuery {
            val taken = OrganizationsTable.selectAll()
                .where { OrganizationsTable.slug eq normalizedSlug }
                .count() > 0
            if (taken) throw CalendarException.Conflict("slug is already taken")
            val now = clock.now()
            val id = OrganizationId.generate()
            OrganizationsTable.insert {
                it[OrganizationsTable.id] = id.toUuid()
                it[OrganizationsTable.slug] = normalizedSlug
                it[OrganizationsTable.displayName] = name
                it[OrganizationsTable.description] = cleanDescription
                it[avatarKey] = null
                it[avatarUpdatedAt] = null
                it[visibility] = OrganizationVisibility.PRIVATE.wire
                it[createdAt] = now
                it[updatedAt] = now
            }
            OrganizationMembersTable.insert {
                it[organizationId] = id.toUuid()
                it[userId] = actorId.toUuid()
                it[role] = OrganizationRole.OWNER.wire
                it[createdAt] = now
            }
            Organization(
                id = id,
                slug = normalizedSlug,
                displayName = name,
                description = cleanDescription,
                avatarKey = null,
                avatarUpdatedAt = null,
                visibility = OrganizationVisibility.PRIVATE,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    suspend fun update(
        actorId: UserId,
        orgId: OrganizationId,
        displayName: String? = null,
        description: String? = null,
        visibility: OrganizationVisibility? = null,
    ): Organization {
        val name = displayName?.let(::requireDisplayName)
        val cleanDescription = description?.let(::cleanDescription)
        return dbQuery {
            val row = organizationRow(orgId) ?: throw CalendarException.NotFound("organization not found")
            val actorRole = roleOrNull(orgId, actorId)
                ?: throw CalendarException.Forbidden("not a member of this organization")
            if (visibility != null && actorRole != OrganizationRole.OWNER) {
                throw CalendarException.Forbidden("only an owner can change organization visibility")
            }
            if ((name != null || description != null) && actorRole == OrganizationRole.MEMBER) {
                throw CalendarException.Forbidden("only owners and admins can update the organization")
            }
            val now = clock.now()
            val updatedName = name ?: row[OrganizationsTable.displayName]
            val updatedDescription = if (description != null) cleanDescription else row[OrganizationsTable.description]
            val updatedVisibility = visibility ?: OrganizationVisibility.fromWire(row[OrganizationsTable.visibility])
                ?: OrganizationVisibility.PRIVATE
            OrganizationsTable.update({ OrganizationsTable.id eq orgId.toUuid() }) {
                it[OrganizationsTable.displayName] = updatedName
                it[OrganizationsTable.description] = updatedDescription
                it[OrganizationsTable.visibility] = updatedVisibility.wire
                it[updatedAt] = now
            }
            row.toOrganization().copy(
                displayName = updatedName,
                description = updatedDescription,
                visibility = updatedVisibility,
                updatedAt = now,
            )
        }
    }

    suspend fun delete(actorId: UserId, orgId: OrganizationId): Boolean = dbQuery {
        organizationRow(orgId) ?: throw CalendarException.NotFound("organization not found")
        if (roleOrNull(orgId, actorId) != OrganizationRole.OWNER) {
            throw CalendarException.Forbidden("only an owner can delete an organization")
        }
        OrganizationsTable.deleteWhere { OrganizationsTable.id eq orgId.toUuid() } > 0
    }

    suspend fun listFor(userId: UserId): List<OrganizationMembership> = dbQuery {
        (OrganizationMembersTable innerJoin OrganizationsTable)
            .selectAll()
            .where { OrganizationMembersTable.userId eq userId.toUuid() }
            .orderBy(OrganizationsTable.displayName to SortOrder.ASC, OrganizationsTable.id to SortOrder.ASC)
            .map { row ->
                OrganizationMembership(
                    organization = row.toOrganization(),
                    role = OrganizationRole.fromWire(row[OrganizationMembersTable.role])
                        ?: OrganizationRole.MEMBER,
                )
            }
    }

    suspend fun byId(orgId: OrganizationId): Organization? = dbQuery {
        organizationRow(orgId)?.toOrganization()
    }

    suspend fun bySlug(slug: String): Organization? = dbQuery {
        val normalized = slug.trim().lowercase()
        OrganizationsTable.selectAll()
            .where { OrganizationsTable.slug eq normalized }
            .singleOrNull()
            ?.toOrganization()
    }

    suspend fun isMember(orgId: OrganizationId, userId: UserId): Boolean = dbQuery {
        roleOrNull(orgId, userId) != null
    }

    suspend fun role(orgId: OrganizationId, userId: UserId): OrganizationRole? = dbQuery {
        roleOrNull(orgId, userId)
    }

    suspend fun members(actorId: UserId, orgId: OrganizationId): List<OrganizationMember> = dbQuery {
        organizationRow(orgId) ?: throw CalendarException.NotFound("organization not found")
        roleOrNull(orgId, actorId)
            ?: throw CalendarException.Forbidden("not a member of this organization")
        memberRows(orgId)
    }

    suspend fun invitations(actorId: UserId, orgId: OrganizationId): List<OrganizationInvitation> = dbQuery {
        organizationRow(orgId) ?: throw CalendarException.NotFound("organization not found")
        val actorRole = roleOrNull(orgId, actorId)
            ?: throw CalendarException.Forbidden("not a member of this organization")
        if (actorRole == OrganizationRole.MEMBER) {
            throw CalendarException.Forbidden("only owners and admins can view invitations")
        }
        OrganizationInvitationsTable.selectAll()
            .where { OrganizationInvitationsTable.organizationId eq orgId.toUuid() }
            .orderBy(
                OrganizationInvitationsTable.createdAt to SortOrder.DESC,
                OrganizationInvitationsTable.id to SortOrder.DESC,
            )
            .map { it.toInvitation() }
    }

    suspend fun addMember(
        actorId: UserId,
        orgId: OrganizationId,
        userId: UserId,
        role: OrganizationRole,
    ): OrganizationMember = dbQuery {
        organizationRow(orgId) ?: throw CalendarException.NotFound("organization not found")
        val actorRole = roleOrNull(orgId, actorId)
            ?: throw CalendarException.Forbidden("not a member of this organization")
        requireGrant(actorRole, role)
        val target = userRow(userId) ?: throw CalendarException.NotFound("user not found")
        if (membershipRow(orgId, userId) != null) {
            throw CalendarException.Conflict("user is already a member")
        }
        val now = clock.now()
        OrganizationMembersTable.insert {
            it[organizationId] = orgId.toUuid()
            it[OrganizationMembersTable.userId] = userId.toUuid()
            it[OrganizationMembersTable.role] = role.wire
            it[createdAt] = now
        }
        OrganizationMember(
            organizationId = orgId,
            userId = userId,
            username = target[UsersTable.username],
            displayName = target[UsersTable.displayName],
            avatarVersion = target[UsersTable.avatarUpdatedAt]?.toEpochMilliseconds(),
            role = role,
            createdAt = now,
        )
    }

    suspend fun updateMemberRole(
        actorId: UserId,
        orgId: OrganizationId,
        userId: UserId,
        role: OrganizationRole,
    ): OrganizationMember = dbQuery {
        organizationRow(orgId) ?: throw CalendarException.NotFound("organization not found")
        val actorRole = roleOrNull(orgId, actorId)
            ?: throw CalendarException.Forbidden("not a member of this organization")
        if (actorRole == OrganizationRole.MEMBER) {
            throw CalendarException.Forbidden("only owners and admins can manage members")
        }
        val targetRole = roleOrNull(orgId, userId)
            ?: throw CalendarException.NotFound("membership not found")
        if ((targetRole != OrganizationRole.MEMBER || role != OrganizationRole.MEMBER) &&
            actorRole != OrganizationRole.OWNER
        ) {
            throw CalendarException.Forbidden("only an owner can manage owners and admins")
        }
        if (targetRole == OrganizationRole.OWNER && role != OrganizationRole.OWNER && ownerCount(orgId) <= 1) {
            throw CalendarException.Forbidden("an organization needs at least one owner")
        }
        if (targetRole != role) {
            OrganizationMembersTable.update({
                (OrganizationMembersTable.organizationId eq orgId.toUuid()) and
                    (OrganizationMembersTable.userId eq userId.toUuid())
            }) {
                it[OrganizationMembersTable.role] = role.wire
            }
        }
        memberRow(orgId, userId) ?: throw CalendarException.NotFound("membership not found")
    }

    suspend fun removeMember(actorId: UserId, orgId: OrganizationId, userId: UserId): Boolean = dbQuery {
        organizationRow(orgId) ?: throw CalendarException.NotFound("organization not found")
        val actorRole = roleOrNull(orgId, actorId)
            ?: throw CalendarException.Forbidden("not a member of this organization")
        if (actorRole == OrganizationRole.MEMBER) {
            throw CalendarException.Forbidden("only owners and admins can manage members")
        }
        val targetRole = roleOrNull(orgId, userId)
            ?: throw CalendarException.NotFound("membership not found")
        if (targetRole != OrganizationRole.MEMBER && actorRole != OrganizationRole.OWNER) {
            throw CalendarException.Forbidden("only an owner can manage owners and admins")
        }
        if (targetRole == OrganizationRole.OWNER && ownerCount(orgId) <= 1) {
            throw CalendarException.Forbidden("an organization needs at least one owner")
        }
        OrganizationMembersTable.deleteWhere {
            (OrganizationMembersTable.organizationId eq orgId.toUuid()) and
                (OrganizationMembersTable.userId eq userId.toUuid())
        } > 0
    }

    suspend fun invite(
        actorId: UserId,
        orgId: OrganizationId,
        identifier: String,
        role: OrganizationRole,
    ): OrganizationInvitationResult {
        val query = identifier.trim()
        if (query.isEmpty()) throw CalendarException.Invalid("username or email is required")
        val inviter = auth.userById(actorId)
            ?: throw CalendarException.Unauthorized("unauthorized")
        val target = auth.userByIdentifier(query)
        val email = if (target == null) {
            if (!query.contains('@')) {
                throw CalendarException.NotFound("no user matches that username or email")
            }
            requireEmail(query)
        } else {
            null
        }
        val token = if (target == null) SessionTokens.generate() else null
        val (invitationId, organization) = dbQuery {
            val organization = organizationRow(orgId)
                ?.toOrganization()
                ?: throw CalendarException.NotFound("organization not found")
            val actorRole = roleOrNull(orgId, actorId)
                ?: throw CalendarException.Forbidden("not a member of this organization")
            requireInviteRole(actorRole, role)
            if (target != null) {
                roleOrNull(orgId, target.id)?.let {
                    throw CalendarException.Conflict("user is already a member")
                }
            }
            val now = clock.now()
            val expiresAt = now + InvitationTtl
            val existing = if (target != null) {
                val targetMatch = if (target.email != null) {
                    (OrganizationInvitationsTable.inviteeUserId eq target.id.toUuid()) or
                        (OrganizationInvitationsTable.email eq target.email)
                } else {
                    OrganizationInvitationsTable.inviteeUserId eq target.id.toUuid()
                }
                OrganizationInvitationsTable.selectAll()
                    .where {
                        (OrganizationInvitationsTable.organizationId eq orgId.toUuid()) and
                            (OrganizationInvitationsTable.status eq OrganizationInvitation.PENDING) and
                            targetMatch
                    }
                    .singleOrNull()
            } else {
                OrganizationInvitationsTable.selectAll()
                    .where {
                        (OrganizationInvitationsTable.organizationId eq orgId.toUuid()) and
                            (OrganizationInvitationsTable.status eq OrganizationInvitation.PENDING) and
                            (OrganizationInvitationsTable.email eq email) and
                            OrganizationInvitationsTable.inviteeUserId.isNull()
                    }
                    .singleOrNull()
            }
            val invitationId = existing?.get(OrganizationInvitationsTable.id) ?: Uuid.random()
            if (existing == null) {
                OrganizationInvitationsTable.insert {
                    it[id] = invitationId
                    it[organizationId] = orgId.toUuid()
                    it[inviterId] = actorId.toUuid()
                    it[inviteeUserId] = target?.id?.toUuid()
                    it[OrganizationInvitationsTable.email] = if (target == null) email else null
                    it[OrganizationInvitationsTable.role] = role.wire
                    it[status] = OrganizationInvitation.PENDING
                    it[tokenHash] = token?.let { value -> SessionTokens.hash(value) }
                    it[OrganizationInvitationsTable.expiresAt] = expiresAt
                    it[createdAt] = now
                    it[respondedAt] = null
                }
            } else {
                OrganizationInvitationsTable.update({ OrganizationInvitationsTable.id eq invitationId }) {
                    it[inviterId] = actorId.toUuid()
                    it[inviteeUserId] = target?.id?.toUuid()
                    it[OrganizationInvitationsTable.email] = if (target == null) email else null
                    it[OrganizationInvitationsTable.role] = role.wire
                    it[status] = OrganizationInvitation.PENDING
                    it[tokenHash] = token?.let { value -> SessionTokens.hash(value) }
                    it[OrganizationInvitationsTable.expiresAt] = expiresAt
                    it[respondedAt] = null
                }
            }
            invitationId to organization
        }
        if (target != null) {
            notifications.create(
                userId = target.id,
                kind = "org.invite",
                title = "${inviter.displayName} invited you to ${organization.displayName}",
                body = "You were invited as ${role.wire}",
                href = "/o/${organization.slug}",
            )
        } else if (email != null && token != null) {
            mail.sendOrganizationInvitation(
                to = email,
                inviterName = inviter.displayName,
                organizationName = organization.displayName,
                role = role.wire,
                link = "/o/${organization.slug}?inviteToken=$token",
            )
        }
        return OrganizationInvitationResult(
            invitationId = invitationId,
            status = OrganizationInvitation.PENDING,
        )
    }

    suspend fun acceptInvitation(actorId: UserId, invitationId: String): OrganizationMembership {
        val id = Uuid.parseOrNull(invitationId.trim())
            ?: throw CalendarException.Invalid("invalid invitation id")
        val user = auth.userById(actorId) ?: throw CalendarException.Unauthorized("unauthorized")
        return dbQuery {
            val row = OrganizationInvitationsTable.selectAll()
                .where { OrganizationInvitationsTable.id eq id }
                .singleOrNull()
                ?: throw CalendarException.NotFound("invitation not found")
            acceptRow(row, actorId, user)
        }
    }

    suspend fun acceptInvitationByToken(actorId: UserId, token: String): OrganizationMembership {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) throw CalendarException.NotFound("invitation not found")
        val user = auth.userById(actorId) ?: throw CalendarException.Unauthorized("unauthorized")
        return dbQuery {
            val row = OrganizationInvitationsTable.selectAll()
                .where { OrganizationInvitationsTable.tokenHash eq SessionTokens.hash(trimmed) }
                .singleOrNull()
                ?: throw CalendarException.NotFound("invitation not found")
            acceptRow(row, actorId, user)
        }
    }

    suspend fun declineInvitation(actorId: UserId, invitationId: String): OrganizationInvitation {
        val id = Uuid.parseOrNull(invitationId.trim())
            ?: throw CalendarException.Invalid("invalid invitation id")
        val user = auth.userById(actorId) ?: throw CalendarException.Unauthorized("unauthorized")
        return dbQuery {
            val row = OrganizationInvitationsTable.selectAll()
                .where { OrganizationInvitationsTable.id eq id }
                .singleOrNull()
                ?: throw CalendarException.NotFound("invitation not found")
            requirePending(row)
            requireInvitee(row, actorId, user)
            val now = clock.now()
            OrganizationInvitationsTable.update({ OrganizationInvitationsTable.id eq id }) {
                it[status] = OrganizationInvitation.DECLINED
                it[respondedAt] = now
            }
            row.toInvitation().copy(status = OrganizationInvitation.DECLINED, respondedAt = now)
        }
    }

    suspend fun revokeInvitation(actorId: UserId, invitationId: String): OrganizationInvitation {
        val id = Uuid.parseOrNull(invitationId.trim())
            ?: throw CalendarException.Invalid("invalid invitation id")
        return dbQuery {
            val row = OrganizationInvitationsTable.selectAll()
                .where { OrganizationInvitationsTable.id eq id }
                .singleOrNull()
                ?: throw CalendarException.NotFound("invitation not found")
            val orgId = OrganizationId(row[OrganizationInvitationsTable.organizationId].toString())
            val actorRole = roleOrNull(orgId, actorId)
                ?: throw CalendarException.Forbidden("not a member of this organization")
            if (actorRole == OrganizationRole.MEMBER) {
                throw CalendarException.Forbidden("only owners and admins can revoke invitations")
            }
            requirePending(row)
            val now = clock.now()
            OrganizationInvitationsTable.update({ OrganizationInvitationsTable.id eq id }) {
                it[status] = OrganizationInvitation.REVOKED
                it[respondedAt] = now
            }
            row.toInvitation().copy(status = OrganizationInvitation.REVOKED, respondedAt = now)
        }
    }

    private fun JdbcTransaction.acceptRow(
        row: ResultRow,
        actorId: UserId,
        user: User,
    ): OrganizationMembership {
        requirePending(row)
        requireInvitee(row, actorId, user)
        val orgId = OrganizationId(row[OrganizationInvitationsTable.organizationId].toString())
        val role = OrganizationRole.fromWire(row[OrganizationInvitationsTable.role])
            ?: OrganizationRole.MEMBER
        val now = clock.now()
        if (membershipRow(orgId, actorId) == null) {
            OrganizationMembersTable.insert {
                it[organizationId] = orgId.toUuid()
                it[userId] = actorId.toUuid()
                it[OrganizationMembersTable.role] = role.wire
                it[createdAt] = now
            }
        }
        OrganizationInvitationsTable.update({ OrganizationInvitationsTable.id eq row[OrganizationInvitationsTable.id] }) {
            it[status] = OrganizationInvitation.ACCEPTED
            it[respondedAt] = now
        }
        val organization = organizationRow(orgId)?.toOrganization()
            ?: throw CalendarException.NotFound("organization not found")
        val currentRole = roleOrNull(orgId, actorId) ?: role
        return OrganizationMembership(organization = organization, role = currentRole)
    }

    private fun requirePending(row: ResultRow) {
        if (row[OrganizationInvitationsTable.status] != OrganizationInvitation.PENDING) {
            throw CalendarException.Conflict("invitation is no longer pending")
        }
        if (row[OrganizationInvitationsTable.expiresAt] <= clock.now()) {
            throw CalendarException.Forbidden("invitation has expired")
        }
    }

    private fun requireInvitee(row: ResultRow, actorId: UserId, user: User) {
        val inviteeUserId = row[OrganizationInvitationsTable.inviteeUserId]
        if (inviteeUserId != null && inviteeUserId == actorId.toUuid()) return
        val email = row[OrganizationInvitationsTable.email]
        val actorEmail = user.email
        if (email != null && actorEmail != null && email.equals(actorEmail, ignoreCase = true)) return
        throw CalendarException.Forbidden("this invitation is for a different user")
    }

    private fun requireGrant(actorRole: OrganizationRole, role: OrganizationRole) {
        when (actorRole) {
            OrganizationRole.OWNER -> Unit
            OrganizationRole.ADMIN -> if (role != OrganizationRole.MEMBER) {
                throw CalendarException.Forbidden("admins may only add members")
            }

            OrganizationRole.MEMBER -> throw CalendarException.Forbidden("members cannot manage members")
        }
    }

    private fun requireInviteRole(actorRole: OrganizationRole, role: OrganizationRole) {
        when (actorRole) {
            OrganizationRole.OWNER -> Unit
            OrganizationRole.ADMIN -> if (role != OrganizationRole.MEMBER) {
                throw CalendarException.Forbidden("admins may only invite members")
            }

            OrganizationRole.MEMBER -> throw CalendarException.Forbidden("members cannot invite")
        }
    }

    private fun JdbcTransaction.organizationRow(orgId: OrganizationId): ResultRow? =
        OrganizationsTable.selectAll()
            .where { OrganizationsTable.id eq orgId.toUuid() }
            .singleOrNull()

    private fun JdbcTransaction.membershipRow(orgId: OrganizationId, userId: UserId): ResultRow? =
        OrganizationMembersTable.selectAll()
            .where {
                (OrganizationMembersTable.organizationId eq orgId.toUuid()) and
                    (OrganizationMembersTable.userId eq userId.toUuid())
            }
            .singleOrNull()

    private fun JdbcTransaction.roleOrNull(orgId: OrganizationId, userId: UserId): OrganizationRole? =
        membershipRow(orgId, userId)
            ?.get(OrganizationMembersTable.role)
            ?.let(OrganizationRole::fromWire)

    private fun JdbcTransaction.ownerCount(orgId: OrganizationId): Long =
        OrganizationMembersTable.selectAll()
            .where {
                (OrganizationMembersTable.organizationId eq orgId.toUuid()) and
                    (OrganizationMembersTable.role eq OrganizationRole.OWNER.wire)
            }
            .count()

    private fun JdbcTransaction.memberRows(orgId: OrganizationId): List<OrganizationMember> =
        (OrganizationMembersTable innerJoin UsersTable)
            .selectAll()
            .where { OrganizationMembersTable.organizationId eq orgId.toUuid() }
            .orderBy(UsersTable.username to SortOrder.ASC)
            .map { row ->
                OrganizationMember(
                    organizationId = orgId,
                    userId = UserId(row[OrganizationMembersTable.userId].toString()),
                    username = row[UsersTable.username],
                    displayName = row[UsersTable.displayName],
                    avatarVersion = row[UsersTable.avatarUpdatedAt]?.toEpochMilliseconds(),
                    role = OrganizationRole.fromWire(row[OrganizationMembersTable.role])
                        ?: OrganizationRole.MEMBER,
                    createdAt = row[OrganizationMembersTable.createdAt],
                )
            }

    private fun JdbcTransaction.memberRow(orgId: OrganizationId, userId: UserId): OrganizationMember? =
        (OrganizationMembersTable innerJoin UsersTable)
            .selectAll()
            .where {
                (OrganizationMembersTable.organizationId eq orgId.toUuid()) and
                    (OrganizationMembersTable.userId eq userId.toUuid())
            }
            .singleOrNull()
            ?.let { row ->
                OrganizationMember(
                    organizationId = orgId,
                    userId = userId,
                    username = row[UsersTable.username],
                    displayName = row[UsersTable.displayName],
                    avatarVersion = row[UsersTable.avatarUpdatedAt]?.toEpochMilliseconds(),
                    role = OrganizationRole.fromWire(row[OrganizationMembersTable.role])
                        ?: OrganizationRole.MEMBER,
                    createdAt = row[OrganizationMembersTable.createdAt],
                )
            }

    private fun JdbcTransaction.userRow(userId: UserId): ResultRow? =
        UsersTable.selectAll()
            .where { UsersTable.id eq userId.toUuid() }
            .singleOrNull()

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }

    private fun requireSlug(slug: String) {
        if (!SlugPattern.matches(slug)) {
            throw CalendarException.Invalid(
                "slug must be 3-32 characters, start with a letter or digit, and contain only " +
                    "lowercase letters, digits, dots, underscores, or hyphens",
            )
        }
        if (slug in ReservedSlugs) {
            throw CalendarException.Conflict("slug is reserved")
        }
    }

    private fun cleanDescription(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.length > MaxDescriptionLength) {
            throw CalendarException.Invalid("description must be at most $MaxDescriptionLength characters")
        }
        return trimmed
    }

    private companion object {
        private val InvitationTtl = 7.days
        private const val MaxDescriptionLength = 500
        private val SlugPattern = Regex("^[a-z0-9][a-z0-9._-]{2,31}$")
        private val ReservedSlugs = setOf(
            "admin",
            "settings",
            "login",
            "register",
            "u",
            "o",
            "c",
            "rss",
            "api",
            "directory",
            "notifications",
            "verify-email",
            "rsvp",
        )
    }
}

private fun ResultRow.toOrganization(): Organization = Organization(
    id = OrganizationId(this[OrganizationsTable.id].toString()),
    slug = this[OrganizationsTable.slug],
    displayName = this[OrganizationsTable.displayName],
    description = this[OrganizationsTable.description],
    avatarKey = this[OrganizationsTable.avatarKey],
    avatarUpdatedAt = this[OrganizationsTable.avatarUpdatedAt],
    visibility = OrganizationVisibility.fromWire(this[OrganizationsTable.visibility])
        ?: OrganizationVisibility.PRIVATE,
    createdAt = this[OrganizationsTable.createdAt],
    updatedAt = this[OrganizationsTable.updatedAt],
)

private fun ResultRow.toInvitation(): OrganizationInvitation = OrganizationInvitation(
    id = this[OrganizationInvitationsTable.id],
    organizationId = OrganizationId(this[OrganizationInvitationsTable.organizationId].toString()),
    inviterId = this[OrganizationInvitationsTable.inviterId]?.let { UserId(it.toString()) },
    inviteeUserId = this[OrganizationInvitationsTable.inviteeUserId]?.let { UserId(it.toString()) },
    email = this[OrganizationInvitationsTable.email],
    role = OrganizationRole.fromWire(this[OrganizationInvitationsTable.role]) ?: OrganizationRole.MEMBER,
    status = this[OrganizationInvitationsTable.status],
    expiresAt = this[OrganizationInvitationsTable.expiresAt],
    createdAt = this[OrganizationInvitationsTable.createdAt],
    respondedAt = this[OrganizationInvitationsTable.respondedAt],
)
