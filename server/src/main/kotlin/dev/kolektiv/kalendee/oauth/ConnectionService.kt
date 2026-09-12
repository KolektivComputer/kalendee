package dev.kolektiv.kalendee.oauth

import dev.kolektiv.kalendee.auth.Accent
import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.PasswordHasher
import dev.kolektiv.kalendee.auth.SessionTokens
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.auth.toUser
import dev.kolektiv.kalendee.auth.toUuid
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.config.AppSettings
import dev.kolektiv.kalendee.db.CalendarConnectionsTable
import dev.kolektiv.kalendee.db.SessionsTable
import dev.kolektiv.kalendee.db.UsersTable
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Function
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory

data class CalendarConnectionSummary(
    val id: String,
    val provider: String,
    val providerName: String,
    val accountEmail: String?,
    val displayName: String?,
    val status: String,
    val lastSyncAt: Instant?,
    val lastError: String?,
)

sealed interface OAuthCallbackOutcome {
    data class Connected(
        val user: User,
        val connectionId: String,
        val sessionToken: String?,
        val returnTo: String?,
    ) : OAuthCallbackOutcome

    data object RegistrationClosed : OAuthCallbackOutcome

    data object EmailTaken : OAuthCallbackOutcome
}

class ConnectionService(
    private val database: Database,
    private val vault: TokenVault,
    private val states: OAuthStateService,
    private val registry: ProviderRegistry,
    private val auth: AuthService,
    private val authSettings: AuthSettings,
    private val hasher: PasswordHasher,
    private val appSettings: AppSettings,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(ConnectionService::class.java)

    fun providers(): List<CalendarProvider> = registry.all()

    suspend fun connectUrl(userId: UserId, providerId: String, returnTo: String?): String {
        val provider = registry.require(providerId)
        if (!provider.enabled) throw CalendarException.Invalid("provider is not configured")
        val pkce = Pkce.generate()
        val redirectUri = callbackUri(provider.id)
        val state = states.create(
            userId = userId,
            provider = provider.id,
            pkce = pkce,
            redirectUri = redirectUri,
            returnTo = safeReturnTo(returnTo),
        )
        return provider.oauthClient().authorizationUrl(state, pkce, redirectUri)
    }

    suspend fun connections(userId: UserId): List<CalendarConnectionSummary> = dbQuery {
        CalendarConnectionsTable.selectAll()
            .where { CalendarConnectionsTable.userId eq userId.toUuid() }
            .orderBy(CalendarConnectionsTable.createdAt to SortOrder.ASC)
            .map { row ->
                val providerId = row[CalendarConnectionsTable.provider]
                CalendarConnectionSummary(
                    id = row[CalendarConnectionsTable.id].toString(),
                    provider = providerId,
                    providerName = registry.byId(providerId)?.displayName ?: providerId,
                    accountEmail = row[CalendarConnectionsTable.accountEmail],
                    displayName = row[CalendarConnectionsTable.displayName],
                    status = row[CalendarConnectionsTable.status],
                    lastSyncAt = row[CalendarConnectionsTable.lastSyncAt],
                    lastError = row[CalendarConnectionsTable.lastError],
                )
            }
    }

    suspend fun disconnect(userId: UserId, connectionId: String): Boolean {
        val uuid = Uuid.parseOrNull(connectionId)
            ?: throw CalendarException.Invalid("invalid connection id")
        val row = dbQuery {
            CalendarConnectionsTable.selectAll()
                .where {
                    (CalendarConnectionsTable.id eq uuid) and
                        (CalendarConnectionsTable.userId eq userId.toUuid())
                }
                .singleOrNull()
        } ?: return false
        revoke(userId, row)
        return dbQuery {
            CalendarConnectionsTable.deleteWhere {
                (CalendarConnectionsTable.id eq uuid) and
                    (CalendarConnectionsTable.userId eq userId.toUuid())
            } > 0
        }
    }

    suspend fun handleCallback(
        providerId: String,
        code: String,
        state: String,
        currentUserId: UserId? = null,
    ): OAuthCallbackOutcome {
        val provider = registry.require(providerId)
        if (!provider.enabled) throw CalendarException.Invalid("provider is not configured")
        val record = states.consume(state, provider.id)
        val client = provider.oauthClient()
        val pkce = PkceChallenge(verifier = record.codeVerifier, challenge = Pkce.challenge(record.codeVerifier))
        val tokens = client.exchange(code, pkce, record.redirectUri)
        val account = client.accountIdentity(tokens)
        val registrationAllowed = auth.isOAuthRegistrationOpen() && auth.isRegistrationOpen()
        val resolution = dbQuery {
            resolveUser(
                account = account,
                requestedUserId = currentUserId ?: record.userId,
                registrationAllowed = registrationAllowed,
            )
        }
        when (resolution) {
            UserResolution.RegistrationClosed -> return OAuthCallbackOutcome.RegistrationClosed
            UserResolution.EmailTaken -> return OAuthCallbackOutcome.EmailTaken
            is UserResolution.Resolved -> return OAuthCallbackOutcome.Connected(
                user = resolution.user,
                connectionId = dbQuery { upsertConnection(resolution.user.id, provider.id, account, tokens) },
                sessionToken = resolution.sessionToken,
                returnTo = safeReturnTo(record.returnTo),
            )
        }
    }

    private sealed interface UserResolution {
        data class Resolved(val user: User, val sessionToken: String?) : UserResolution

        data object RegistrationClosed : UserResolution

        data object EmailTaken : UserResolution
    }

    private fun JdbcTransaction.resolveUser(
        account: ProviderAccount,
        requestedUserId: UserId?,
        registrationAllowed: Boolean,
    ): UserResolution {
        if (requestedUserId != null) {
            val existing = UsersTable.selectAll()
                .where { UsersTable.id eq requestedUserId.toUuid() }
                .singleOrNull()
                ?.toUser()
            if (existing != null) return UserResolution.Resolved(existing, sessionToken = null)
        }
        val email = account.email?.takeIf { it.isNotBlank() }
        var emailTaken = false
        if (email != null) {
            val normalized = normalizeEmail(email)
            val verified = UsersTable.selectAll()
                .where {
                    (LowerNullable(UsersTable.email) eq normalized) and
                        (UsersTable.emailVerified eq true)
                }
                .singleOrNull()
                ?.toUser()
            if (verified != null) {
                return UserResolution.Resolved(verified, sessionToken = issueSession(verified))
            }
            emailTaken = UsersTable.selectAll()
                .where {
                    (LowerNullable(UsersTable.email) eq normalized) or
                        (UsersTable.emailNormalized eq normalized)
                }
                .count() > 0
        }
        if (!registrationAllowed) return UserResolution.RegistrationClosed
        if (emailTaken) return UserResolution.EmailTaken
        val user = createUser(account)
        return UserResolution.Resolved(user, sessionToken = issueSession(user))
    }

    private fun JdbcTransaction.createUser(account: ProviderAccount): User {
        val now = clock.now()
        val email = account.email?.takeIf { it.isNotBlank() }
        val user = User(
            id = UserId.generate(),
            username = uniqueUsername(account),
            displayName = account.displayName?.takeIf { it.isNotBlank() }
                ?: email?.substringBefore('@')
                ?: "User",
            email = email,
            emailVerified = true,
            timeZone = "UTC",
            accent = Accent.Default,
            admin = UsersTable.selectAll().count() == 0L,
            createdAt = now,
        )
        UsersTable.insert {
            it[id] = user.id.toUuid()
            it[username] = user.username
            it[passwordHash] = hasher.hash(SessionTokens.generate())
            it[displayName] = user.displayName
            it[timeZone] = user.timeZone
            it[accent] = user.accent
            it[isAdmin] = user.admin
            it[createdAt] = now
            it[updatedAt] = now
            it[UsersTable.email] = user.email
            it[emailNormalized] = email?.let(::normalizeEmail)
            it[emailVerified] = true
            it[avatarKey] = null
            it[avatarUpdatedAt] = null
        }
        return user
    }

    private fun JdbcTransaction.uniqueUsername(account: ProviderAccount): String {
        val base = (account.email?.substringBefore('@') ?: account.displayName ?: "user")
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9._-]"), "")
            .take(24)
            .ifBlank { "user" }
        if (usernameAvailable(base)) return base
        var suffix = 2
        while (true) {
            val candidate = "$base-$suffix"
            if (usernameAvailable(candidate)) return candidate
            suffix++
        }
    }

    private fun JdbcTransaction.usernameAvailable(username: String): Boolean =
        UsersTable.selectAll()
            .where { UsersTable.username eq username }
            .count() == 0L

    private fun JdbcTransaction.issueSession(user: User): String {
        val token = SessionTokens.generate()
        val now = clock.now()
        SessionsTable.insert {
            it[id] = Uuid.random()
            it[userId] = user.id.toUuid()
            it[tokenHash] = SessionTokens.hash(token)
            it[expiresAt] = now + authSettings.sessionTtl
            it[createdAt] = now
        }
        return token
    }

    private fun JdbcTransaction.upsertConnection(
        userId: UserId,
        providerId: String,
        account: ProviderAccount,
        tokens: OAuthTokens,
    ): String {
        val now = clock.now()
        val aad = connectionAad(userId, providerId, account.externalId)
        val existing = CalendarConnectionsTable.selectAll()
            .where {
                (CalendarConnectionsTable.userId eq userId.toUuid()) and
                    (CalendarConnectionsTable.provider eq providerId) and
                    (CalendarConnectionsTable.externalAccountId eq account.externalId)
            }
            .singleOrNull()
        val refreshToken = tokens.refreshToken ?: existing?.let { openRefreshToken(it, aad) }
        val accessSealed = vault.seal(tokens.accessToken.toByteArray(Charsets.UTF_8), aad)
        val refreshSealed = refreshToken?.let { vault.seal(it.toByteArray(Charsets.UTF_8), aad) }
        val scopes = tokens.scopes.takeIf { it.isNotEmpty() }?.joinToString(" ")
        if (existing == null) {
            val id = Uuid.random()
            CalendarConnectionsTable.insert {
                it[CalendarConnectionsTable.id] = id
                it[CalendarConnectionsTable.userId] = userId.toUuid()
                it[provider] = providerId
                it[externalAccountId] = account.externalId
                it[accountEmail] = account.email
                it[displayName] = account.displayName
                it[accessTokenCiphertext] = accessSealed.ciphertext
                it[accessTokenNonce] = accessSealed.nonce
                it[refreshTokenCiphertext] = refreshSealed?.ciphertext
                it[refreshTokenNonce] = refreshSealed?.nonce
                it[tokenKeyVersion] = accessSealed.keyVersion
                it[tokenExpiresAt] = tokens.expiresAt
                it[CalendarConnectionsTable.scopes] = scopes
                it[status] = "active"
                it[createdAt] = now
                it[updatedAt] = now
            }
            return id.toString()
        }
        val existingId = existing[CalendarConnectionsTable.id]
        CalendarConnectionsTable.update({ CalendarConnectionsTable.id eq existingId }) {
            it[accountEmail] = account.email
            it[displayName] = account.displayName
            it[accessTokenCiphertext] = accessSealed.ciphertext
            it[accessTokenNonce] = accessSealed.nonce
            it[refreshTokenCiphertext] = refreshSealed?.ciphertext
            it[refreshTokenNonce] = refreshSealed?.nonce
            it[tokenKeyVersion] = accessSealed.keyVersion
            it[tokenExpiresAt] = tokens.expiresAt
            it[CalendarConnectionsTable.scopes] = scopes
            it[status] = "active"
            it[lastError] = null
            it[updatedAt] = now
        }
        return existingId.toString()
    }

    private fun openRefreshToken(row: ResultRow, aad: ByteArray): String? {
        val ciphertext = row[CalendarConnectionsTable.refreshTokenCiphertext] ?: return null
        val nonce = row[CalendarConnectionsTable.refreshTokenNonce] ?: return null
        val version = row[CalendarConnectionsTable.tokenKeyVersion]
        return try {
            String(vault.open(SealedToken(ciphertext, nonce, version), aad), Charsets.UTF_8)
        } catch (_: TokenVaultException) {
            null
        }
    }

    private suspend fun revoke(userId: UserId, row: ResultRow) {
        val providerId = row[CalendarConnectionsTable.provider]
        val provider = registry.byId(providerId) ?: return
        try {
            val aad = connectionAad(userId, providerId, row[CalendarConnectionsTable.externalAccountId])
            val version = row[CalendarConnectionsTable.tokenKeyVersion]
            val accessToken = String(
                vault.open(
                    SealedToken(
                        ciphertext = row[CalendarConnectionsTable.accessTokenCiphertext],
                        nonce = row[CalendarConnectionsTable.accessTokenNonce],
                        keyVersion = version,
                    ),
                    aad,
                ),
                Charsets.UTF_8,
            )
            val refreshToken = openRefreshToken(row, aad)
            provider.oauthClient().revoke(OAuthTokens(accessToken = accessToken, refreshToken = refreshToken))
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
            log.warn("revoking tokens for provider {} failed; removing the local connection anyway", providerId)
        }
    }

    private fun callbackUri(providerId: String): String {
        val base = appSettings.baseUrl.trim().trimEnd('/')
        val path = "/api/v1/oauth/$providerId/callback"
        return if (base.isEmpty()) path else "$base$path"
    }

    private fun connectionAad(userId: UserId, providerId: String, externalAccountId: String): ByteArray =
        "calendar-connection:$userId:$providerId:$externalAccountId".toByteArray(Charsets.UTF_8)

    private fun normalizeEmail(email: String): String = email.trim().lowercase()

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }

    companion object {
        fun safeReturnTo(raw: String?): String? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            if (!value.startsWith("/") || value.startsWith("//")) return null
            if (value.contains('\\') || value.contains('\n') || value.contains('\r')) return null
            val path = value.substringBefore('?').substringBefore('#')
            if (path.contains("://")) return null
            return value
        }
    }
}

private class LowerNullable(private val expression: Expression<String?>) : Function<String>(VarCharColumnType()) {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("LOWER(").append(expression).append(')')
    }
}
