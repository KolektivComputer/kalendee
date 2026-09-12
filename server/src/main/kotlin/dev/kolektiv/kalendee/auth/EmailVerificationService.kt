package dev.kolektiv.kalendee.auth

import dev.kolektiv.kalendee.db.EmailTokensTable
import dev.kolektiv.kalendee.db.UsersTable
import dev.kolektiv.kalendee.mail.MailService
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

class EmailVerificationService(
    private val database: Database,
    private val mail: MailService,
    private val settings: AuthSettings,
    private val clock: Clock,
) {
    private val lastResend = ConcurrentHashMap<UserId, Instant>()

    suspend fun sendVerification(user: User): Boolean {
        val email = user.email ?: return false
        val token = SessionTokens.generate()
        val now = clock.now()
        dbQuery {
            EmailTokensTable.deleteWhere {
                (EmailTokensTable.userId eq user.id.toUuid()) and
                    (EmailTokensTable.purpose eq PurposeVerifyEmail)
            }
            EmailTokensTable.insert {
                it[id] = Uuid.random()
                it[userId] = user.id.toUuid()
                it[purpose] = PurposeVerifyEmail
                it[tokenHash] = SessionTokens.hash(token)
                it[EmailTokensTable.email] = email
                it[expiresAt] = now + settings.emailVerificationTtl
                it[usedAt] = null
                it[createdAt] = now
            }
        }
        mail.sendEmailVerification(email, mail.absoluteLink("/verify-email?token=$token"))
        return true
    }

    suspend fun verify(token: String): User? {
        if (token.isBlank()) return null
        val tokenHash = SessionTokens.hash(token)
        val now = clock.now()
        return dbQuery {
            val row = EmailTokensTable.selectAll()
                .where {
                    (EmailTokensTable.tokenHash eq tokenHash) and
                        (EmailTokensTable.purpose eq PurposeVerifyEmail)
                }
                .singleOrNull()
                ?: return@dbQuery null
            if (row[EmailTokensTable.usedAt] != null || row[EmailTokensTable.expiresAt] <= now) {
                return@dbQuery null
            }
            val consumed = EmailTokensTable.update({
                (EmailTokensTable.id eq row[EmailTokensTable.id]) and EmailTokensTable.usedAt.isNull()
            }) {
                it[usedAt] = now
            }
            if (consumed == 0) return@dbQuery null
            val userId = UserId(row[EmailTokensTable.userId].toString())
            val updated = UsersTable.update({ UsersTable.id eq userId.toUuid() }) {
                it[emailVerified] = true
                it[updatedAt] = now
            }
            if (updated == 0) return@dbQuery null
            UsersTable.selectAll()
                .where { UsersTable.id eq userId.toUuid() }
                .singleOrNull()
                ?.toUser()
        }
    }

    suspend fun resumeThrottled(userId: UserId): Boolean {
        val now = clock.now()
        lastResend.entries.removeIf { now - it.value >= ResendWindow }
        var allowed = false
        lastResend.compute(userId) { _, previous ->
            if (previous == null || now - previous >= ResendWindow) {
                allowed = true
                now
            } else {
                previous
            }
        }
        return allowed
    }

    suspend fun resendFor(usernameOrEmail: String): Boolean {
        val query = usernameOrEmail.trim().lowercase()
        if (query.isEmpty()) return false
        val user = dbQuery {
            UsersTable.selectAll()
                .where { (UsersTable.username eq query) or (UsersTable.email eq query) }
                .singleOrNull()
                ?.toUser()
        } ?: return false
        if (user.email == null || user.emailVerified) return false
        if (!resumeThrottled(user.id)) return false
        return sendVerification(user)
    }

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }

    private companion object {
        const val PurposeVerifyEmail = "verify_email"
        val ResendWindow = 60.seconds
    }
}
