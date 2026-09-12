package dev.kolektiv.kalendee.oauth

import dev.kolektiv.kalendee.auth.SessionTokens
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.auth.toUuid
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.db.OAuthStatesTable
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

data class OAuthStateRecord(
    val state: String,
    val userId: UserId?,
    val provider: String,
    val codeVerifier: String,
    val redirectUri: String,
    val returnTo: String?,
)

class OAuthStateService(
    private val database: Database,
    private val vault: TokenVault,
    private val clock: Clock,
    private val ttl: Duration = 10.minutes,
) {
    suspend fun create(
        userId: UserId?,
        provider: String,
        pkce: PkceChallenge,
        redirectUri: String,
        returnTo: String?,
    ): String = dbQuery {
        val now = clock.now()
        OAuthStatesTable.deleteWhere { expiresAt lessEq now }
        val state = SessionTokens.generate()
        val sealed = vault.seal(pkce.verifier.toByteArray(Charsets.UTF_8), aad(state, provider))
        OAuthStatesTable.insert {
            it[OAuthStatesTable.state] = state
            it[OAuthStatesTable.userId] = userId?.toUuid()
            it[OAuthStatesTable.provider] = provider
            it[codeVerifierCiphertext] = sealed.ciphertext
            it[codeVerifierNonce] = sealed.nonce
            it[OAuthStatesTable.redirectUri] = redirectUri
            it[OAuthStatesTable.returnTo] = returnTo
            it[createdAt] = now
            it[expiresAt] = now + ttl
        }
        state
    }

    suspend fun consume(state: String, provider: String): OAuthStateRecord = dbQuery {
        val row = OAuthStatesTable.selectAll()
            .where { OAuthStatesTable.state eq state }
            .singleOrNull()
            ?: throw CalendarException.Invalid("unknown oauth state")
        if (row[OAuthStatesTable.provider] != provider) {
            throw CalendarException.Invalid("oauth state provider mismatch")
        }
        if (row[OAuthStatesTable.usedAt] != null) {
            throw CalendarException.Invalid("oauth state already used")
        }
        val now = clock.now()
        if (row[OAuthStatesTable.expiresAt] <= now) {
            throw CalendarException.Invalid("oauth state expired")
        }
        val verifier = String(
            vault.open(
                SealedToken(
                    ciphertext = row[OAuthStatesTable.codeVerifierCiphertext],
                    nonce = row[OAuthStatesTable.codeVerifierNonce],
                    keyVersion = vault.currentKeyVersion,
                ),
                aad(state, provider),
            ),
            Charsets.UTF_8,
        )
        OAuthStatesTable.update({ OAuthStatesTable.state eq state }) {
            it[usedAt] = now
        }
        OAuthStateRecord(
            state = state,
            userId = row[OAuthStatesTable.userId]?.let { UserId(it.toString()) },
            provider = row[OAuthStatesTable.provider],
            codeVerifier = verifier,
            redirectUri = row[OAuthStatesTable.redirectUri],
            returnTo = row[OAuthStatesTable.returnTo],
        )
    }

    private fun aad(state: String, provider: String): ByteArray =
        "oauth-state:$provider:$state".toByteArray(Charsets.UTF_8)

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }
}
