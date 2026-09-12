package dev.kolektiv.kalendee.groups

import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.auth.toUser
import dev.kolektiv.kalendee.auth.toUuid
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.db.UserGroupMembersTable
import dev.kolektiv.kalendee.db.UserGroupsTable
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
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

data class Group(
    val id: String,
    val name: String,
    val isSystem: Boolean,
    val storageQuotaBytes: Long?,
    val memberCount: Long,
)

class GroupService(
    private val database: Database,
    private val settings: AuthSettings,
    private val clock: Clock,
) {
    suspend fun seedSystemGroups() {
        dbQuery {
            val now = clock.now()
            ensureGroup(DefaultGroupName, isSystem = true, now)
            ensureGroup(AdminGroupName, isSystem = true, now)
            settings.superadminUsername?.let { username ->
                UsersTable.update({ UsersTable.username eq username }) {
                    it[isAdmin] = true
                    it[isSuperadmin] = true
                    it[updatedAt] = now
                }
            }
            syncAdminMembership(now)
        }
    }

    suspend fun listGroups(): List<Group> = dbQuery {
        UserGroupsTable.selectAll()
            .orderBy(UserGroupsTable.name to SortOrder.ASC)
            .map { row ->
                val id = row[UserGroupsTable.id]
                val name = row[UserGroupsTable.name]
                Group(
                    id = id.toString(),
                    name = name,
                    isSystem = row[UserGroupsTable.isSystem],
                    storageQuotaBytes = row[UserGroupsTable.storageQuotaBytes],
                    memberCount = memberCount(id, name),
                )
            }
    }

    suspend fun createGroup(name: String, quotaBytes: Long? = null): Group {
        val cleanName = name.trim()
        requireGroupName(cleanName)
        requireQuota(quotaBytes)
        return dbQuery {
            if (isReserved(cleanName)) {
                throw CalendarException.Conflict("name is reserved for a system group")
            }
            val taken = UserGroupsTable.selectAll()
                .where { UserGroupsTable.name eq cleanName }
                .count() > 0
            if (taken) throw CalendarException.Conflict("name is already taken")
            val now = clock.now()
            val id = Uuid.random()
            UserGroupsTable.insert {
                it[UserGroupsTable.id] = id
                it[UserGroupsTable.name] = cleanName
                it[isSystem] = false
                it[storageQuotaBytes] = quotaBytes
                it[createdAt] = now
            }
            Group(
                id = id.toString(),
                name = cleanName,
                isSystem = false,
                storageQuotaBytes = quotaBytes,
                memberCount = 0,
            )
        }
    }

    suspend fun updateGroup(
        id: String,
        name: String? = null,
        quotaBytes: Long? = null,
        clearQuota: Boolean = false,
    ): Group {
        val groupId = parseGroupId(id)
        requireQuota(quotaBytes)
        return dbQuery {
            val row = UserGroupsTable.selectAll()
                .where { UserGroupsTable.id eq groupId }
                .singleOrNull()
                ?: throw CalendarException.NotFound("group not found")
            val isSystem = row[UserGroupsTable.isSystem]
            val cleanName = name?.trim()
            if (cleanName != null && cleanName != row[UserGroupsTable.name]) {
                if (isSystem) throw CalendarException.Forbidden("system groups cannot be renamed")
                requireGroupName(cleanName)
                if (isReserved(cleanName)) {
                    throw CalendarException.Conflict("name is reserved for a system group")
                }
                val taken = UserGroupsTable.selectAll()
                    .where { (UserGroupsTable.name eq cleanName) and (UserGroupsTable.id neq groupId) }
                    .count() > 0
                if (taken) throw CalendarException.Conflict("name is already taken")
            }
            val updatedName = cleanName ?: row[UserGroupsTable.name]
            val updatedQuota = when {
                clearQuota -> null
                quotaBytes != null -> quotaBytes
                else -> row[UserGroupsTable.storageQuotaBytes]
            }
            UserGroupsTable.update({ UserGroupsTable.id eq groupId }) {
                it[UserGroupsTable.name] = updatedName
                it[storageQuotaBytes] = updatedQuota
            }
            Group(
                id = groupId.toString(),
                name = updatedName,
                isSystem = isSystem,
                storageQuotaBytes = updatedQuota,
                memberCount = memberCount(groupId, updatedName),
            )
        }
    }

    suspend fun deleteGroup(id: String): Boolean {
        val groupId = parseGroupId(id)
        return dbQuery {
            val row = UserGroupsTable.selectAll()
                .where { UserGroupsTable.id eq groupId }
                .singleOrNull()
                ?: throw CalendarException.NotFound("group not found")
            if (row[UserGroupsTable.isSystem]) {
                throw CalendarException.Forbidden("system groups cannot be deleted")
            }
            UserGroupsTable.deleteWhere { UserGroupsTable.id eq groupId } > 0
        }
    }

    suspend fun members(groupId: String): List<User> {
        val parsed = parseGroupId(groupId)
        return dbQuery {
            val row = UserGroupsTable.selectAll()
                .where { UserGroupsTable.id eq parsed }
                .singleOrNull()
                ?: throw CalendarException.NotFound("group not found")
            listMembers(parsed, row[UserGroupsTable.name])
        }
    }

    suspend fun setMembers(groupId: String, userIds: List<UserId>, actorId: UserId): List<User> {
        val parsed = parseGroupId(groupId)
        val unique = userIds.distinct()
        return dbQuery {
            val actor = userRow(actorId) ?: throw CalendarException.Unauthorized("unauthorized")
            if (!actor[UsersTable.isAdmin] && !actor[UsersTable.isSuperadmin]) {
                throw CalendarException.Forbidden("admin only")
            }
            val group = UserGroupsTable.selectAll()
                .where { UserGroupsTable.id eq parsed }
                .singleOrNull()
                ?: throw CalendarException.NotFound("group not found")
            val name = group[UserGroupsTable.name]
            if (name == DefaultGroupName) {
                throw CalendarException.Forbidden("default group membership is implicit")
            }
            val wanted = unique.map { it.toUuid() }.toSet()
            val wantedUsers = if (wanted.isEmpty()) {
                emptyMap()
            } else {
                UsersTable.selectAll()
                    .where { UsersTable.id inList wanted.toList() }
                    .associate { it[UsersTable.id] to it }
            }
            if (wantedUsers.size != wanted.size) {
                throw CalendarException.NotFound("user not found")
            }
            val now = clock.now()
            if (name == AdminGroupName) {
                setAdminGroupMembers(parsed, wanted, actor, actorId.toUuid(), now)
            } else {
                val current = UserGroupMembersTable.selectAll()
                    .where { UserGroupMembersTable.groupId eq parsed }
                    .map { it[UserGroupMembersTable.userId] }
                    .toSet()
                (current - wanted).forEach { userId ->
                    UserGroupMembersTable.deleteWhere {
                        (UserGroupMembersTable.groupId eq parsed) and (UserGroupMembersTable.userId eq userId)
                    }
                }
                (wanted - current).forEach { userId ->
                    UserGroupMembersTable.insert {
                        it[UserGroupMembersTable.groupId] = parsed
                        it[UserGroupMembersTable.userId] = userId
                        it[UserGroupMembersTable.addedBy] = actorId.toUuid()
                        it[createdAt] = now
                    }
                }
            }
            listMembers(parsed, name)
        }
    }

    suspend fun groupsFor(userId: UserId): List<String> = dbQuery {
        val explicit = (UserGroupMembersTable innerJoin UserGroupsTable)
            .selectAll()
            .where { UserGroupMembersTable.userId eq userId.toUuid() }
            .map { it[UserGroupsTable.name] }
            .sorted()
        (listOf(DefaultGroupName) + explicit).distinct()
    }

    suspend fun effectiveQuota(userId: UserId): Long? = dbQuery {
        val user = userRow(userId) ?: throw CalendarException.NotFound("user not found")
        if (user[UsersTable.isAdmin] || user[UsersTable.isSuperadmin]) return@dbQuery null
        val groupIds = UserGroupMembersTable.selectAll()
            .where { UserGroupMembersTable.userId eq userId.toUuid() }
            .map { it[UserGroupMembersTable.groupId] }
        val quotas = UserGroupsTable.selectAll()
            .where {
                (UserGroupsTable.name eq DefaultGroupName) or (UserGroupsTable.id inList groupIds)
            }
            .mapNotNull { it[UserGroupsTable.storageQuotaBytes] }
        quotas.minOrNull()
    }

    suspend fun usage(userId: UserId): Long = dbQuery {
        userRow(userId)?.get(UsersTable.avatarBytes)
            ?: throw CalendarException.NotFound("user not found")
    }

    suspend fun assertCanStore(userId: UserId, additionalBytes: Long) {
        if (additionalBytes <= 0) return
        val user = dbQuery { userRow(userId) } ?: throw CalendarException.NotFound("user not found")
        if (user[UsersTable.isAdmin] || user[UsersTable.isSuperadmin]) return
        val quota = effectiveQuota(userId) ?: return
        val used = user[UsersTable.avatarBytes]
        if (used + additionalBytes > quota) {
            throw CalendarException.Forbidden("storage quota exceeded")
        }
    }

    suspend fun isAdmin(userId: UserId): Boolean = dbQuery {
        userRow(userId)?.get(UsersTable.isAdmin) ?: false
    }

    suspend fun isSuperadmin(userId: UserId): Boolean = dbQuery {
        userRow(userId)?.get(UsersTable.isSuperadmin) ?: false
    }

    suspend fun setSuperadmin(userId: UserId, enabled: Boolean): Boolean = dbQuery {
        val now = clock.now()
        val updated = UsersTable.update({ UsersTable.id eq userId.toUuid() }) {
            it[isSuperadmin] = enabled
            if (enabled) it[isAdmin] = true
            it[updatedAt] = now
        }
        if (updated > 0) syncAdminMembership(now)
        updated > 0
    }

    suspend fun setAdminMembership(userId: UserId, admin: Boolean) {
        dbQuery {
            val now = clock.now()
            UsersTable.update({ UsersTable.id eq userId.toUuid() }) {
                it[isAdmin] = admin
                it[updatedAt] = now
            }
            syncAdminMembership(now)
        }
    }

    private fun JdbcTransaction.setAdminGroupMembers(
        groupId: Uuid,
        wanted: Set<Uuid>,
        actor: ResultRow,
        actorId: Uuid,
        now: Instant,
    ) {
        val current = UsersTable.selectAll()
            .where { UsersTable.isAdmin eq true }
            .map { it[UsersTable.id] }
            .toSet()
        val added = wanted - current
        val removed = current - wanted
        val actorIsSuperadmin = actor[UsersTable.isSuperadmin]
        if (removed.isNotEmpty()) {
            if (actorId in removed) {
                throw CalendarException.Forbidden("cannot remove your own admin access")
            }
            if (!actorIsSuperadmin) {
                throw CalendarException.Forbidden("only a superadmin can remove admin")
            }
            removed.forEach { userId ->
                val target = UsersTable.selectAll()
                    .where { UsersTable.id eq userId }
                    .single()
                if (target[UsersTable.isSuperadmin]) {
                    throw CalendarException.Forbidden("a superadmin cannot be demoted")
                }
            }
            removed.forEach { userId ->
                UsersTable.update({ UsersTable.id eq userId }) {
                    it[isAdmin] = false
                    it[updatedAt] = now
                }
                UserGroupMembersTable.deleteWhere {
                    (UserGroupMembersTable.groupId eq groupId) and (UserGroupMembersTable.userId eq userId)
                }
            }
        }
        added.forEach { userId ->
            UsersTable.update({ UsersTable.id eq userId }) {
                it[isAdmin] = true
                it[updatedAt] = now
            }
            upsertMember(groupId, userId, actorId, now)
        }
        (current intersect wanted).forEach { userId ->
            upsertMember(groupId, userId, null, now)
        }
    }

    private fun JdbcTransaction.listMembers(groupId: Uuid, name: String): List<User> = when (name) {
        DefaultGroupName -> UsersTable.selectAll()
            .orderBy(UsersTable.username to SortOrder.ASC)
            .map { it.toUser() }

        AdminGroupName -> UsersTable.selectAll()
            .where { UsersTable.isAdmin eq true }
            .orderBy(UsersTable.username to SortOrder.ASC)
            .map { it.toUser() }

        else -> {
            val ids = UserGroupMembersTable.selectAll()
                .where { UserGroupMembersTable.groupId eq groupId }
                .map { it[UserGroupMembersTable.userId] }
            if (ids.isEmpty()) {
                emptyList()
            } else {
                UsersTable.selectAll()
                    .where { UsersTable.id inList ids }
                    .orderBy(UsersTable.username to SortOrder.ASC)
                    .map { it.toUser() }
            }
        }
    }

    private fun JdbcTransaction.memberCount(groupId: Uuid, name: String): Long = when (name) {
        DefaultGroupName -> UsersTable.selectAll().count()
        AdminGroupName -> UsersTable.selectAll().where { UsersTable.isAdmin eq true }.count()
        else -> UserGroupMembersTable.selectAll()
            .where { UserGroupMembersTable.groupId eq groupId }
            .count()
    }

    private fun JdbcTransaction.ensureGroup(name: String, isSystem: Boolean, now: Instant): Uuid {
        val existing = UserGroupsTable.selectAll()
            .where { UserGroupsTable.name eq name }
            .singleOrNull()
        if (existing != null) return existing[UserGroupsTable.id]
        val id = Uuid.random()
        UserGroupsTable.insert {
            it[UserGroupsTable.id] = id
            it[UserGroupsTable.name] = name
            it[UserGroupsTable.isSystem] = isSystem
            it[storageQuotaBytes] = null
            it[createdAt] = now
        }
        return id
    }

    private fun JdbcTransaction.syncAdminMembership(now: Instant) {
        val adminGroupId = UserGroupsTable.selectAll()
            .where { UserGroupsTable.name eq AdminGroupName }
            .singleOrNull()
            ?.get(UserGroupsTable.id)
            ?: return
        val admins = UsersTable.selectAll()
            .where { UsersTable.isAdmin eq true }
            .map { it[UsersTable.id] }
            .toSet()
        val members = UserGroupMembersTable.selectAll()
            .where { UserGroupMembersTable.groupId eq adminGroupId }
            .map { it[UserGroupMembersTable.userId] }
            .toSet()
        (admins - members).forEach { userId ->
            upsertMember(adminGroupId, userId, null, now)
        }
        (members - admins).forEach { userId ->
            UserGroupMembersTable.deleteWhere {
                (UserGroupMembersTable.groupId eq adminGroupId) and (UserGroupMembersTable.userId eq userId)
            }
        }
    }

    private fun JdbcTransaction.upsertMember(groupId: Uuid, userId: Uuid, addedBy: Uuid?, now: Instant) {
        val exists = UserGroupMembersTable.selectAll()
            .where {
                (UserGroupMembersTable.groupId eq groupId) and (UserGroupMembersTable.userId eq userId)
            }
            .count() > 0
        if (!exists) {
            UserGroupMembersTable.insert {
                it[UserGroupMembersTable.groupId] = groupId
                it[UserGroupMembersTable.userId] = userId
                it[UserGroupMembersTable.addedBy] = addedBy
                it[createdAt] = now
            }
        }
    }

    private fun JdbcTransaction.userRow(userId: UserId): ResultRow? = UsersTable.selectAll()
        .where { UsersTable.id eq userId.toUuid() }
        .singleOrNull()

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }

    private fun parseGroupId(raw: String): Uuid =
        Uuid.parseOrNull(raw) ?: throw CalendarException.Invalid("invalid group id")

    private fun requireGroupName(name: String) {
        if (name.isEmpty() || name.length > 64) {
            throw CalendarException.Invalid("name must be 1-64 characters")
        }
        if (!GroupNamePattern.matches(name)) {
            throw CalendarException.Invalid(
                "name must contain only letters, digits, spaces, dots, underscores, or hyphens",
            )
        }
    }

    private fun requireQuota(quotaBytes: Long?) {
        if (quotaBytes != null && quotaBytes < 0) {
            throw CalendarException.Invalid("storageQuotaBytes must not be negative")
        }
    }

    private fun isReserved(name: String): Boolean =
        name.equals(DefaultGroupName, ignoreCase = true) || name.equals(AdminGroupName, ignoreCase = true)

    companion object {
        const val DefaultGroupName = "default"
        const val AdminGroupName = "admin"
        private val GroupNamePattern = Regex("^[A-Za-z0-9][A-Za-z0-9 ._-]*$")
    }
}
