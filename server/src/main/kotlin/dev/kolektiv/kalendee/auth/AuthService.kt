package dev.kolektiv.kalendee.auth

import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.OrganizationId
import dev.kolektiv.kalendee.calendar.OrganizationVisibility
import dev.kolektiv.kalendee.config.AppSettings
import dev.kolektiv.kalendee.db.AppSettingsTable
import dev.kolektiv.kalendee.db.LoginDevicesTable
import dev.kolektiv.kalendee.db.OrganizationMembersTable
import dev.kolektiv.kalendee.db.OrganizationsTable
import dev.kolektiv.kalendee.db.SessionsTable
import dev.kolektiv.kalendee.db.UsersTable
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory

data class AuthSession(
    val user: User,
    val token: String,
    val expiresAt: Instant,
)

data class RegistrationResult(
    val session: AuthSession?,
    val verificationRequired: Boolean,
    val email: String?,
)

data class LoginResult(
    val session: AuthSession?,
    val verificationRequired: Boolean,
    val email: String?,
)

class AuthService(
    private val database: Database,
    private val hasher: PasswordHasher,
    private val settings: AuthSettings,
    private val appSettings: AppSettings,
    private val clock: Clock,
    private val verification: EmailVerificationService,
) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)
    private val throttle = LoginThrottle(clock)

    suspend fun seedAdmin(): Boolean {
        val password = settings.adminPassword ?: return false
        val validated = RegisterUser(username = settings.adminUsername, password = password).validated()
        return dbQuery {
            val existing = UsersTable.selectAll()
                .where { UsersTable.username eq validated.username }
                .singleOrNull()
            val now = clock.now()
            if (existing == null) {
                val id = UserId.generate()
                UsersTable.insert {
                    it[UsersTable.id] = id.toUuid()
                    it[username] = validated.username
                    it[passwordHash] = hasher.hash(validated.password)
                    it[displayName] = validated.username
                    it[timeZone] = "UTC"
                    it[accent] = Accent.Default
                    it[isAdmin] = true
                    it[createdAt] = now
                    it[updatedAt] = now
                    it[email] = null
                    it[emailVerified] = false
                    it[avatarKey] = null
                    it[avatarUpdatedAt] = null
                }
                log.info("Created admin user '{}'", validated.username)
                true
            } else {
                val passwordChanged = !hasher.matches(validated.password, existing[UsersTable.passwordHash])
                val promote = !existing[UsersTable.isAdmin]
                if (!passwordChanged && !promote) return@dbQuery false
                UsersTable.update({ UsersTable.id eq existing[UsersTable.id] }) {
                    if (passwordChanged) it[passwordHash] = hasher.hash(validated.password)
                    if (promote) it[isAdmin] = true
                    it[updatedAt] = now
                }
                if (passwordChanged) log.info("Updated password for admin user '{}'", validated.username)
                if (promote) log.info("Granted admin to user '{}'", validated.username)
                true
            }
        }
    }

    suspend fun register(command: RegisterUser): RegistrationResult {
        val validated = command.validated()
        val (user, policy) = dbQuery {
            if (!registrationOpen()) {
                throw CalendarException.Forbidden("registration is closed")
            }
            val email = validated.email
            val policy = emailVerificationPolicy()
            if (policy == EmailVerificationPolicy.Required && email == null) {
                throw CalendarException.Invalid("email is required")
            }
            if (email != null) {
                val emailTaken = UsersTable.selectAll()
                    .where { UsersTable.email eq email }
                    .count() > 0
                if (emailTaken) {
                    throw CalendarException.Conflict("email taken")
                }
            }
            val taken = UsersTable.selectAll()
                .where { UsersTable.username eq validated.username }
                .count() > 0
            if (taken) {
                throw CalendarException.Conflict("username taken")
            }
            val now = clock.now()
            val id = UserId.generate()
            val firstUser = UsersTable.selectAll().count() == 0L
            UsersTable.insert {
                it[UsersTable.id] = id.toUuid()
                it[username] = validated.username
                it[passwordHash] = hasher.hash(validated.password)
                it[displayName] = validated.username
                it[timeZone] = "UTC"
                it[accent] = Accent.Default
                it[isAdmin] = firstUser
                it[createdAt] = now
                it[updatedAt] = now
                it[UsersTable.email] = email
                it[emailVerified] = false
                it[avatarKey] = null
                it[avatarUpdatedAt] = null
            }
            User(
                id = id,
                username = validated.username,
                displayName = validated.username,
                email = email,
                emailVerified = false,
                timeZone = "UTC",
                accent = Accent.Default,
                admin = firstUser,
                createdAt = now,
            ) to policy
        }
        val verificationRequired = user.email != null && !user.emailVerified
        if (verificationRequired) {
            verification.sendVerification(user)
        }
        if (policy == EmailVerificationPolicy.Required) {
            return RegistrationResult(session = null, verificationRequired = true, email = user.email)
        }
        val session = dbQuery { issueSession(user, clock.now()) }
        return RegistrationResult(session = session, verificationRequired = verificationRequired, email = user.email)
    }

    suspend fun login(command: LoginUser): LoginResult {
        val normalized = command.normalized()
        throttle.check(normalized.username)
        val result = dbQuery {
            val row = UsersTable.selectAll()
                .where { UsersTable.username eq normalized.username }
                .singleOrNull()
            val passwordHash = row?.get(UsersTable.passwordHash)
            if (!hasher.matchesOrDummy(normalized.password, passwordHash) || row == null) {
                null
            } else {
                val user = row.toUser()
                val verificationRequired = user.email != null && !user.emailVerified
                if (emailVerificationPolicy() == EmailVerificationPolicy.Required && verificationRequired) {
                    LoginResult(session = null, verificationRequired = true, email = user.email)
                } else {
                    LoginResult(
                        session = issueSession(user, clock.now()),
                        verificationRequired = verificationRequired,
                        email = user.email,
                    )
                }
            }
        }
        if (result == null) {
            throttle.recordFailure(normalized.username)
            throw CalendarException.Unauthorized("invalid username or password")
        }
        throttle.recordSuccess(normalized.username)
        return result
    }

    suspend fun logout(token: String?) {
        if (token.isNullOrBlank()) return
        val tokenHash = SessionTokens.hash(token)
        dbQuery {
            SessionsTable.deleteWhere { SessionsTable.tokenHash eq tokenHash }
        }
    }

    suspend fun isRegistrationOpen(): Boolean = dbQuery { registrationOpen() }

    suspend fun setRegistrationOpen(open: Boolean) {
        dbQuery {
            upsertAppSetting(RegistrationKey, if (open) "open" else "closed")
        }
    }

    suspend fun isOAuthRegistrationOpen(): Boolean = dbQuery { oauthRegistrationOpen() }

    suspend fun setOAuthRegistrationOpen(open: Boolean) {
        dbQuery {
            upsertAppSetting(OauthRegistrationKey, if (open) "open" else "closed")
        }
    }

    suspend fun emailVerificationPolicy(): EmailVerificationPolicy = dbQuery { emailVerificationPolicy() }

    suspend fun setEmailVerificationPolicy(policy: EmailVerificationPolicy) {
        dbQuery {
            upsertAppSetting(EmailVerificationKey, policy.wire)
        }
    }

    suspend fun publicAccess(): String = dbQuery { instancePublicAccess().wire }

    suspend fun setPublicAccess(mode: PublicAccessMode) {
        if (mode == PublicAccessMode.INHERIT) {
            throw CalendarException.Invalid("public access must be public or signed_in")
        }
        dbQuery {
            upsertAppSetting(PublicAccessKey, mode.wire)
        }
    }

    suspend fun usersPublicAccess(userId: UserId): PublicAccessMode = dbQuery {
        UsersTable.selectAll()
            .where { UsersTable.id eq userId.toUuid() }
            .singleOrNull()
            ?.get(UsersTable.publicAccess)
            ?.let(PublicAccessMode::fromWire)
            ?: PublicAccessMode.INHERIT
    }

    suspend fun setUserPublicAccess(userId: UserId, mode: PublicAccessMode): User? {
        val updated = dbQuery {
            UsersTable.update({ UsersTable.id eq userId.toUuid() }) {
                it[publicAccess] = mode.wire
            }
        }
        if (updated == 0) return null
        return userById(userId)
    }

    suspend fun effectivePublicAccess(calendar: Calendar): PublicAccessMode {
        val organizationId = calendar.organizationId
        if (organizationId != null && organizationVisibility(organizationId) != OrganizationVisibility.PUBLIC) {
            return PublicAccessMode.SIGNED_IN
        }
        val calendarMode = calendar.accessMode
        if (calendarMode != PublicAccessMode.INHERIT) return calendarMode
        val userMode = usersPublicAccess(calendar.ownerId)
        if (userMode != PublicAccessMode.INHERIT) return userMode
        return dbQuery { instancePublicAccess() }
    }

    /**
     * Returns whether a viewer with an optional session may see a calendar that
     * is exposed outside the normal membership/share checks (public link, RSS,
     * availability, RSVP-by-id). Private organizations only expose their
     * calendars to members.
     */
    suspend fun canViewPublic(calendar: Calendar, viewerId: UserId?): Boolean =
        canViewPublic(calendar.organizationId, viewerId)

    suspend fun canViewPublic(organizationId: OrganizationId?, viewerId: UserId?): Boolean {
        if (organizationId == null) return true
        if (organizationVisibility(organizationId) != OrganizationVisibility.PRIVATE) return true
        if (viewerId == null) return false
        return isOrganizationMember(organizationId, viewerId)
    }

    suspend fun organizationVisibility(organizationId: OrganizationId): OrganizationVisibility? = dbQuery {
        OrganizationsTable.selectAll()
            .where { OrganizationsTable.id eq organizationId.toUuid() }
            .singleOrNull()
            ?.get(OrganizationsTable.visibility)
            ?.let(OrganizationVisibility::fromWire)
    }

    suspend fun isOrganizationMember(organizationId: OrganizationId, userId: UserId): Boolean = dbQuery {
        OrganizationMembersTable.selectAll()
            .where {
                (OrganizationMembersTable.organizationId eq organizationId.toUuid()) and
                    (OrganizationMembersTable.userId eq userId.toUuid())
            }
            .count() > 0
    }

    suspend fun listUsers(): List<User> = dbQuery {
        UsersTable.selectAll()
            .orderBy(UsersTable.username to SortOrder.ASC)
            .map { it.toUser() }
    }

    suspend fun userById(id: UserId): User? = dbQuery {
        UsersTable.selectAll()
            .where { UsersTable.id eq id.toUuid() }
            .singleOrNull()
            ?.toUser()
    }

    suspend fun userByIdentifier(identifier: String): User? {
        val query = identifier.trim().lowercase()
        if (query.isEmpty()) return null
        return dbQuery {
            UsersTable.selectAll()
                .where { (UsersTable.username eq query) or (UsersTable.email eq query) }
                .singleOrNull()
                ?.toUser()
        }
    }

    suspend fun userFor(token: String): User? {
        val tokenHash = SessionTokens.hash(token)
        val now = clock.now()
        return dbQuery {
            val row = (SessionsTable innerJoin UsersTable)
                .selectAll()
                .where { SessionsTable.tokenHash eq tokenHash }
                .singleOrNull()
                ?: return@dbQuery null
            if (row[SessionsTable.expiresAt] <= now) {
                SessionsTable.deleteWhere { SessionsTable.tokenHash eq tokenHash }
                return@dbQuery null
            }
            row.toUser()
        }
    }

    suspend fun updateUser(id: UserId, command: UpdateUser): User? {
        val validated = command.validated()
        val (updated, emailChanged) = dbQuery {
            val existing = UsersTable.selectAll()
                .where { UsersTable.id eq id.toUuid() }
                .singleOrNull()
                ?.toUser()
                ?: return@dbQuery null
            if (validated.clearEmail && emailVerificationPolicy() == EmailVerificationPolicy.Required) {
                throw CalendarException.Invalid("email is required")
            }
            val email = when {
                validated.clearEmail -> null
                validated.email != null -> validated.email
                else -> existing.email
            }
            val newEmail = validated.email
            if (newEmail != null && newEmail != existing.email) {
                val taken = UsersTable.selectAll()
                    .where { (UsersTable.email eq newEmail) and (UsersTable.id neq id.toUuid()) }
                    .count() > 0
                if (taken) {
                    throw CalendarException.Conflict("email taken")
                }
            }
            val emailChanged = newEmail != null && newEmail != existing.email
            val now = clock.now()
            val updated = existing.copy(
                displayName = validated.displayName ?: existing.displayName,
                timeZone = validated.timeZone ?: existing.timeZone,
                accent = validated.accent ?: existing.accent,
                email = email,
                emailVerified = if (emailChanged) false else existing.emailVerified,
            )
            UsersTable.update({ UsersTable.id eq id.toUuid() }) {
                it[displayName] = updated.displayName
                it[timeZone] = updated.timeZone
                it[accent] = updated.accent
                it[UsersTable.email] = email
                it[emailVerified] = updated.emailVerified
                it[updatedAt] = now
            }
            updated to emailChanged
        } ?: return null
        if (emailChanged) {
            verification.sendVerification(updated)
        }
        return updated
    }

    suspend fun recordLoginDevice(userId: UserId, ip: String?, userAgent: String?): Boolean = dbQuery {
        val fingerprint = fingerprint(ip, userAgent)
        val now = clock.now()
        val existing = LoginDevicesTable.selectAll()
            .where {
                (LoginDevicesTable.userId eq userId.toUuid()) and
                    (LoginDevicesTable.fingerprint eq fingerprint)
            }
            .singleOrNull()
        if (existing == null) {
            LoginDevicesTable.insert {
                it[LoginDevicesTable.userId] = userId.toUuid()
                it[LoginDevicesTable.fingerprint] = fingerprint
                it[LoginDevicesTable.userAgent] = userAgent
                it[LoginDevicesTable.ip] = ip
                it[firstSeenAt] = now
                it[lastSeenAt] = now
            }
            true
        } else {
            LoginDevicesTable.update({
                (LoginDevicesTable.userId eq userId.toUuid()) and
                    (LoginDevicesTable.fingerprint eq fingerprint)
            }) {
                it[LoginDevicesTable.userAgent] = userAgent
                it[LoginDevicesTable.ip] = ip
                it[lastSeenAt] = now
            }
            false
        }
    }

    suspend fun avatarKey(userId: UserId): String? = dbQuery {
        UsersTable.selectAll()
            .where { UsersTable.id eq userId.toUuid() }
            .singleOrNull()
            ?.get(UsersTable.avatarKey)
    }

    suspend fun setAvatar(userId: UserId, key: String?, byteSize: Long = 0): User? {
        val updated = dbQuery {
            UsersTable.update({ UsersTable.id eq userId.toUuid() }) {
                it[avatarKey] = key
                it[avatarUpdatedAt] = if (key == null) null else clock.now()
                it[avatarBytes] = if (key == null) 0 else byteSize.coerceAtLeast(0)
            }
        }
        if (updated == 0) return null
        return userById(userId)
    }

    private fun JdbcTransaction.registrationOpen(): Boolean {
        val override = AppSettingsTable.selectAll()
            .where { AppSettingsTable.key eq RegistrationKey }
            .singleOrNull()
            ?.get(AppSettingsTable.value)
        return when (override) {
            "open" -> true
            "closed" -> false
            else -> when (settings.registration) {
                RegistrationPolicy.Closed -> false
                RegistrationPolicy.Open -> true
                RegistrationPolicy.FirstUser -> UsersTable.selectAll().count() == 0L
            }
        }
    }

    private fun JdbcTransaction.oauthRegistrationOpen(): Boolean {
        val override = AppSettingsTable.selectAll()
            .where { AppSettingsTable.key eq OauthRegistrationKey }
            .singleOrNull()
            ?.get(AppSettingsTable.value)
        return when (override) {
            "open" -> true
            "closed" -> false
            else -> settings.oauthRegistration
        }
    }

    private fun JdbcTransaction.emailVerificationPolicy(): EmailVerificationPolicy {
        val override = AppSettingsTable.selectAll()
            .where { AppSettingsTable.key eq EmailVerificationKey }
            .singleOrNull()
            ?.get(AppSettingsTable.value)
        return if (override == null) settings.emailVerification else EmailVerificationPolicy.parse(override)
    }

    private fun JdbcTransaction.instancePublicAccess(): PublicAccessMode {
        val override = AppSettingsTable.selectAll()
            .where { AppSettingsTable.key eq PublicAccessKey }
            .singleOrNull()
            ?.get(AppSettingsTable.value)
        return PublicAccessMode.fromWire(override)
            ?: PublicAccessMode.fromWire(appSettings.publicAccess)
            ?: PublicAccessMode.PUBLIC
    }

    private fun JdbcTransaction.upsertAppSetting(key: String, value: String) {
        val existing = AppSettingsTable.selectAll()
            .where { AppSettingsTable.key eq key }
            .singleOrNull()
        if (existing == null) {
            AppSettingsTable.insert {
                it[AppSettingsTable.key] = key
                it[AppSettingsTable.value] = value
            }
        } else {
            AppSettingsTable.update({ AppSettingsTable.key eq key }) {
                it[AppSettingsTable.value] = value
            }
        }
    }

    private fun JdbcTransaction.issueSession(user: User, now: Instant): AuthSession {
        val token = SessionTokens.generate()
        val expiresAt = now + settings.sessionTtl
        SessionsTable.insert {
            it[id] = Uuid.random()
            it[userId] = user.id.toUuid()
            it[tokenHash] = SessionTokens.hash(token)
            it[SessionsTable.expiresAt] = expiresAt
            it[createdAt] = now
        }
        return AuthSession(user = user, token = token, expiresAt = expiresAt)
    }

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }

    private fun fingerprint(ip: String?, userAgent: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$ip|$userAgent".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

private const val RegistrationKey = "registration"
private const val OauthRegistrationKey = "oauth_registration"
private const val EmailVerificationKey = "email_verification"
private const val PublicAccessKey = "public_access"

internal fun org.jetbrains.exposed.v1.core.ResultRow.toUser(): User = User(
    id = UserId(this[UsersTable.id].toString()),
    username = this[UsersTable.username],
    displayName = this[UsersTable.displayName],
    email = this[UsersTable.email],
    emailVerified = this[UsersTable.emailVerified],
    avatarVersion = this[UsersTable.avatarUpdatedAt]?.toEpochMilliseconds(),
    timeZone = this[UsersTable.timeZone],
    accent = this[UsersTable.accent],
    admin = this[UsersTable.isAdmin],
    publicAccess = PublicAccessMode.fromWire(this[UsersTable.publicAccess]) ?: PublicAccessMode.INHERIT,
    createdAt = this[UsersTable.createdAt],
)

internal fun UserId.toUuid(): Uuid = Uuid.parse(value)
internal fun OrganizationId.toUuid(): Uuid = Uuid.parse(value)
