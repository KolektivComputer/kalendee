package dev.kolektiv.kalendee.friends

import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.auth.toUuid
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.db.FriendshipsTable
import dev.kolektiv.kalendee.db.UsersTable
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

const val FriendshipPending = "pending"
const val FriendshipAccepted = "accepted"

const val RelationshipNone = "none"
const val RelationshipPendingOut = "pending_out"
const val RelationshipPendingIn = "pending_in"
const val RelationshipFriends = "friends"

@Serializable
data class FriendSummary(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarVersion: Long? = null,
)

@Serializable
data class FriendRequestSummary(
    val id: String,
    val user: FriendSummary,
    val createdAt: Instant,
)

@Serializable
data class FriendRequestResult(
    val status: String,
    val friend: FriendSummary,
)

@Serializable
data class UserSearchResult(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarVersion: Long? = null,
    val relationship: String,
)

class FriendshipService(
    private val database: Database,
    private val clock: Clock,
) {
    suspend fun friends(userId: UserId): List<FriendSummary> = dbQuery {
        val me = userId.toUuid()
        val accepted = FriendshipsTable.selectAll()
            .where {
                (FriendshipsTable.status eq FriendshipAccepted) and
                    ((FriendshipsTable.requesterId eq me) or (FriendshipsTable.addresseeId eq me))
            }
            .map { row ->
                if (row[FriendshipsTable.requesterId] == me) {
                    row[FriendshipsTable.addresseeId]
                } else {
                    row[FriendshipsTable.requesterId]
                }
            }
            .distinct()
        if (accepted.isEmpty()) return@dbQuery emptyList()
        UsersTable.selectAll()
            .where { UsersTable.id inList accepted }
            .orderBy(UsersTable.displayName to SortOrder.ASC, UsersTable.username to SortOrder.ASC)
            .map { it.toFriendSummary() }
    }

    suspend fun incomingRequests(userId: UserId): List<FriendRequestSummary> = dbQuery {
        FriendshipsTable.innerJoin(
            UsersTable,
            onColumn = { FriendshipsTable.requesterId },
            otherColumn = { UsersTable.id },
        )
            .selectAll()
            .where {
                (FriendshipsTable.addresseeId eq userId.toUuid()) and
                    (FriendshipsTable.status eq FriendshipPending)
            }
            .orderBy(FriendshipsTable.createdAt to SortOrder.DESC)
            .map { row ->
                FriendRequestSummary(
                    id = row[FriendshipsTable.id].toString(),
                    user = row.toFriendSummary(),
                    createdAt = row[FriendshipsTable.createdAt],
                )
            }
    }

    suspend fun sendRequest(userId: UserId, usernameOrEmail: String): FriendRequestResult = dbQuery {
        val query = usernameOrEmail.trim().lowercase()
        val target = if (query.isEmpty()) {
            null
        } else {
            UsersTable.selectAll()
                .where { (UsersTable.username eq query) or (UsersTable.email eq query) }
                .singleOrNull()
        } ?: throw CalendarException.NotFound("no user matches that username or email")
        val me = userId.toUuid()
        val them = target[UsersTable.id]
        if (me == them) {
            throw CalendarException.Invalid("you cannot add yourself")
        }
        val now = clock.now()
        val friend = target.toFriendSummary()
        val existing = FriendshipsTable.selectAll()
            .where { pairMatches(me, them, FriendshipsTable.requesterId, FriendshipsTable.addresseeId) }
            .singleOrNull()
        if (existing != null) {
            when {
                existing[FriendshipsTable.status] == FriendshipAccepted ->
                    throw CalendarException.Conflict("already friends")

                existing[FriendshipsTable.requesterId] == me ->
                    throw CalendarException.Conflict("request already sent")

                else -> {
                    FriendshipsTable.update({ FriendshipsTable.id eq existing[FriendshipsTable.id] }) {
                        it[status] = FriendshipAccepted
                        it[respondedAt] = now
                    }
                    return@dbQuery FriendRequestResult(status = FriendshipAccepted, friend = friend)
                }
            }
        }
        FriendshipsTable.insert {
            it[id] = Uuid.random()
            it[requesterId] = me
            it[addresseeId] = them
            it[status] = FriendshipPending
            it[createdAt] = now
            it[respondedAt] = null
        }
        FriendRequestResult(status = FriendshipPending, friend = friend)
    }

    suspend fun accept(userId: UserId, friendshipId: String): FriendRequestSummary? = dbQuery {
        val row = pendingRequest(userId, friendshipId)
        val now = clock.now()
        FriendshipsTable.update({ FriendshipsTable.id eq row[FriendshipsTable.id] }) {
            it[status] = FriendshipAccepted
            it[respondedAt] = now
        }
        val requester = UsersTable.selectAll()
            .where { UsersTable.id eq row[FriendshipsTable.requesterId] }
            .singleOrNull()
            ?: return@dbQuery null
        FriendRequestSummary(
            id = row[FriendshipsTable.id].toString(),
            user = requester.toFriendSummary(),
            createdAt = row[FriendshipsTable.createdAt],
        )
    }

    suspend fun decline(userId: UserId, friendshipId: String) {
        dbQuery {
            val row = pendingRequest(userId, friendshipId)
            FriendshipsTable.deleteWhere { FriendshipsTable.id eq row[FriendshipsTable.id] }
        }
    }

    suspend fun remove(userId: UserId, otherUserId: UserId) {
        dbQuery {
            val me = userId.toUuid()
            val them = otherUserId.toUuid()
            FriendshipsTable.deleteWhere {
                (FriendshipsTable.status eq FriendshipAccepted) and
                    pairMatches(me, them, FriendshipsTable.requesterId, FriendshipsTable.addresseeId)
            }
        }
    }

    suspend fun relationship(userId: UserId, otherId: UserId): String = dbQuery {
        val me = userId.toUuid()
        val row = FriendshipsTable.selectAll()
            .where { pairMatches(me, otherId.toUuid(), FriendshipsTable.requesterId, FriendshipsTable.addresseeId) }
            .singleOrNull()
            ?: return@dbQuery RelationshipNone
        when {
            row[FriendshipsTable.status] == FriendshipAccepted -> RelationshipFriends
            row[FriendshipsTable.requesterId] == me -> RelationshipPendingOut
            else -> RelationshipPendingIn
        }
    }

    suspend fun searchUsers(userId: UserId, query: String, limit: Int = 10): List<UserSearchResult> = dbQuery {
        val term = query.trim().lowercase()
        if (term.isEmpty()) return@dbQuery emptyList()
        val pattern = "%$term%"
        val matches = UsersTable.selectAll()
            .where {
                (UsersTable.id neq userId.toUuid()) and
                    ((UsersTable.username.lowerCase() like pattern) or (UsersTable.displayName.lowerCase() like pattern))
            }
            .orderBy(UsersTable.username to SortOrder.ASC)
            .limit(limit)
            .toList()
        if (matches.isEmpty()) return@dbQuery emptyList()
        val relationships = relationshipsAmong(userId, matches.map { it[UsersTable.id] })
        matches.map { row ->
            val id = row[UsersTable.id]
            UserSearchResult(
                userId = id.toString(),
                username = row[UsersTable.username],
                displayName = row[UsersTable.displayName],
                avatarVersion = row[UsersTable.avatarUpdatedAt]?.toEpochMilliseconds(),
                relationship = relationships[id] ?: RelationshipNone,
            )
        }
    }

    private fun JdbcTransaction.pendingRequest(userId: UserId, friendshipId: String): ResultRow {
        val id = Uuid.parseOrNull(friendshipId)
            ?: throw CalendarException.Invalid("invalid request id")
        val row = FriendshipsTable.selectAll()
            .where { FriendshipsTable.id eq id }
            .singleOrNull()
            ?: throw CalendarException.NotFound("request not found")
        if (row[FriendshipsTable.addresseeId] != userId.toUuid()) {
            throw CalendarException.Forbidden("only the recipient can respond")
        }
        if (row[FriendshipsTable.status] != FriendshipPending) {
            throw CalendarException.Conflict("request already handled")
        }
        return row
    }

    private fun JdbcTransaction.relationshipsAmong(userId: UserId, others: List<Uuid>): Map<Uuid, String> {
        if (others.isEmpty()) return emptyMap()
        val me = userId.toUuid()
        return FriendshipsTable.selectAll()
            .where {
                ((FriendshipsTable.requesterId eq me) and (FriendshipsTable.addresseeId inList others)) or
                    ((FriendshipsTable.addresseeId eq me) and (FriendshipsTable.requesterId inList others))
            }
            .associate { row ->
                val other = if (row[FriendshipsTable.requesterId] == me) {
                    row[FriendshipsTable.addresseeId]
                } else {
                    row[FriendshipsTable.requesterId]
                }
                other to when {
                    row[FriendshipsTable.status] == FriendshipAccepted -> RelationshipFriends
                    row[FriendshipsTable.requesterId] == me -> RelationshipPendingOut
                    else -> RelationshipPendingIn
                }
            }
    }

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }
}

private fun pairMatches(
    one: Uuid,
    other: Uuid,
    requesterId: Column<Uuid>,
    addresseeId: Column<Uuid>,
): Op<Boolean> = ((requesterId eq one) and (addresseeId eq other)) or
    ((requesterId eq other) and (addresseeId eq one))

private fun ResultRow.toFriendSummary(): FriendSummary = FriendSummary(
    userId = this[UsersTable.id].toString(),
    username = this[UsersTable.username],
    displayName = this[UsersTable.displayName],
    avatarVersion = this[UsersTable.avatarUpdatedAt]?.toEpochMilliseconds(),
)
