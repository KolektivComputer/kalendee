package dev.kolektiv.kalendee.notifications

import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.auth.toUuid
import dev.kolektiv.kalendee.db.NotificationsTable
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

data class Notification(
    val id: Uuid,
    val kind: String,
    val title: String,
    val body: String?,
    val href: String?,
    val readAt: Instant?,
    val createdAt: Instant,
)

class NotificationService(
    private val database: Database,
    private val clock: Clock,
) {
    suspend fun create(
        userId: UserId,
        kind: String,
        title: String,
        body: String? = null,
        href: String? = null,
    ): Notification = dbQuery {
        val now = clock.now()
        val id = Uuid.random()
        NotificationsTable.insert {
            it[NotificationsTable.id] = id
            it[NotificationsTable.userId] = userId.toUuid()
            it[NotificationsTable.kind] = kind
            it[NotificationsTable.title] = title
            it[NotificationsTable.body] = body
            it[NotificationsTable.href] = href
            it[readAt] = null
            it[createdAt] = now
        }
        Notification(
            id = id,
            kind = kind,
            title = title,
            body = body,
            href = href,
            readAt = null,
            createdAt = now,
        )
    }

    suspend fun list(userId: UserId, limit: Int = 100): List<Notification> = dbQuery {
        NotificationsTable.selectAll()
            .where { NotificationsTable.userId eq userId.toUuid() }
            .orderBy(NotificationsTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toNotification() }
    }

    suspend fun unreadCount(userId: UserId): Int = dbQuery {
        NotificationsTable.selectAll()
            .where {
                (NotificationsTable.userId eq userId.toUuid()) and NotificationsTable.readAt.isNull()
            }
            .count()
            .toInt()
    }

    suspend fun markRead(userId: UserId, id: String): Boolean {
        val notificationId = Uuid.parseOrNull(id) ?: return false
        return dbQuery {
            NotificationsTable.update({
                (NotificationsTable.id eq notificationId) and
                    (NotificationsTable.userId eq userId.toUuid()) and
                    NotificationsTable.readAt.isNull()
            }) {
                it[readAt] = clock.now()
            } > 0
        }
    }

    suspend fun markAllRead(userId: UserId): Int = dbQuery {
        NotificationsTable.update({
            (NotificationsTable.userId eq userId.toUuid()) and NotificationsTable.readAt.isNull()
        }) {
            it[readAt] = clock.now()
        }
    }

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }
}

private fun ResultRow.toNotification(): Notification = Notification(
    id = this[NotificationsTable.id],
    kind = this[NotificationsTable.kind],
    title = this[NotificationsTable.title],
    body = this[NotificationsTable.body],
    href = this[NotificationsTable.href],
    readAt = this[NotificationsTable.readAt],
    createdAt = this[NotificationsTable.createdAt],
)
