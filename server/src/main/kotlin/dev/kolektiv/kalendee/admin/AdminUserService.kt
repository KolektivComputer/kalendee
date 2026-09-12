package dev.kolektiv.kalendee.admin

import dev.kolektiv.kalendee.auth.EmailVerificationService
import dev.kolektiv.kalendee.auth.PasswordHasher
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.auth.requireDisplayName
import dev.kolektiv.kalendee.auth.requireEmail
import dev.kolektiv.kalendee.auth.requirePassword
import dev.kolektiv.kalendee.auth.toUuid
import dev.kolektiv.kalendee.auth.toUser
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.db.UsersTable
import dev.kolektiv.kalendee.groups.GroupService
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

data class AdminUserUpdate(
    val displayName: String? = null,
    val email: String? = null,
    val password: String? = null,
    val isAdmin: Boolean? = null,
)

class AdminUserService(
    private val database: Database,
    private val hasher: PasswordHasher,
    private val verification: EmailVerificationService,
    private val groups: GroupService,
    private val clock: Clock,
) {
    suspend fun updateUserAsAdmin(
        actorId: UserId,
        userId: UserId,
        command: AdminUserUpdate,
    ): User {
        val displayName = command.displayName?.let(::requireDisplayName)
        val email = command.email?.let(::requireEmail)
        command.password?.let(::requirePassword)
        val (updated, emailChanged, adminChanged) = dbQuery {
            val actor = userRow(actorId) ?: throw CalendarException.Unauthorized("unauthorized")
            requireAdminActor(actor)
            val target = userRow(userId) ?: throw CalendarException.NotFound("user not found")
            assertCanModify(actor, target, userId, actorId, command.isAdmin)
            val emailChanged = email != null && email != target[UsersTable.email]
            if (emailChanged) {
                val taken = UsersTable.selectAll()
                    .where { (UsersTable.email eq email) and (UsersTable.id neq userId.toUuid()) }
                    .count() > 0
                if (taken) throw CalendarException.Conflict("email taken")
            }
            val now = clock.now()
            val passwordHash = command.password?.let(hasher::hash)
            val adminChanged = command.isAdmin != null && command.isAdmin != target[UsersTable.isAdmin]
            UsersTable.update({ UsersTable.id eq userId.toUuid() }) {
                displayName?.let { value -> it[UsersTable.displayName] = value }
                email?.let { value -> it[UsersTable.email] = value }
                if (emailChanged) it[emailVerified] = false
                passwordHash?.let { value -> it[UsersTable.passwordHash] = value }
                if (command.isAdmin != null) it[isAdmin] = command.isAdmin
                it[updatedAt] = now
            }
            val updated = checkNotNull(userRow(userId)).toUser()
            Triple(updated, emailChanged, adminChanged)
        }
        if (adminChanged) {
            groups.setAdminMembership(userId, command.isAdmin == true)
        }
        if (emailChanged) {
            verification.sendVerification(updated)
        }
        return updated
    }

    suspend fun deleteUserAsAdmin(actorId: UserId, userId: UserId): Boolean = dbQuery {
        val actor = userRow(actorId) ?: throw CalendarException.Unauthorized("unauthorized")
        requireAdminActor(actor)
        if (userId == actorId) {
            throw CalendarException.Forbidden("cannot delete yourself")
        }
        val target = userRow(userId) ?: throw CalendarException.NotFound("user not found")
        if (target[UsersTable.isSuperadmin] && !actor[UsersTable.isSuperadmin]) {
            throw CalendarException.Forbidden("cannot delete a superadmin")
        }
        UsersTable.deleteWhere { UsersTable.id eq userId.toUuid() } > 0
    }

    private fun assertCanModify(
        actor: ResultRow,
        target: ResultRow,
        userId: UserId,
        actorId: UserId,
        admin: Boolean?,
    ) {
        val actorIsSuperadmin = actor[UsersTable.isSuperadmin]
        if (target[UsersTable.isSuperadmin] && !actorIsSuperadmin) {
            throw CalendarException.Forbidden("cannot modify a superadmin")
        }
        if (admin == false) {
            if (userId == actorId) {
                throw CalendarException.Forbidden("cannot remove your own admin access")
            }
            if (target[UsersTable.isSuperadmin]) {
                throw CalendarException.Forbidden("a superadmin cannot be demoted")
            }
            if (target[UsersTable.isAdmin] && !actorIsSuperadmin) {
                throw CalendarException.Forbidden("only a superadmin can remove admin")
            }
        }
    }

    private fun requireAdminActor(actor: ResultRow) {
        if (!actor[UsersTable.isAdmin] && !actor[UsersTable.isSuperadmin]) {
            throw CalendarException.Forbidden("admin only")
        }
    }

    private fun JdbcTransaction.userRow(userId: UserId): ResultRow? = UsersTable.selectAll()
        .where { UsersTable.id eq userId.toUuid() }
        .singleOrNull()

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }
}
