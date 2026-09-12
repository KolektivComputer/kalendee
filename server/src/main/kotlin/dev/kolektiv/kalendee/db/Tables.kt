package dev.kolektiv.kalendee.db

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.datetime.InstantColumnType
import org.jetbrains.exposed.v1.core.vendors.currentDialect

private object TimestamptzInstantColumnType : InstantColumnType<Instant>() {
    override fun toInstant(value: Instant): Instant = value
    override fun fromInstant(instant: Instant): Instant = instant
    override fun sqlType(): String = currentDialect.dataTypeProvider.timestampWithTimeZoneType()
}

private fun Table.instant(name: String): Column<Instant> =
    registerColumn(name, TimestamptzInstantColumnType)

object UsersTable : Table("users") {
    val id = uuid("id")
    val username = text("username").uniqueIndex()
    val passwordHash = text("password_hash")
    val displayName = text("display_name")
    val timeZone = text("time_zone")
    val showHolidays = bool("show_holidays")
    val accent = text("accent")
    val isAdmin = bool("is_admin")
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")
    val email = text("email").nullable()
    val emailNormalized = text("email_normalized").nullable().uniqueIndex()
    val emailVerified = bool("email_verified")
    val avatarKey = text("avatar_key").nullable()
    val avatarUpdatedAt = instant("avatar_updated_at").nullable()
    val notifyAtStart = bool("notify_at_start")
    val publicAccess = text("public_access").default("inherit")
    val isSuperadmin = bool("is_superadmin").default(false)
    val avatarBytes = long("avatar_bytes").default(0)

    override val primaryKey = PrimaryKey(id)
}

object OrganizationsTable : Table("organizations") {
    val id = uuid("id")
    val slug = text("slug").uniqueIndex()
    val displayName = text("display_name")
    val description = text("description").nullable()
    val avatarKey = text("avatar_key").nullable()
    val avatarUpdatedAt = instant("avatar_updated_at").nullable()
    val visibility = text("visibility").default("private")
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object OrganizationMembersTable : Table("organization_members") {
    val organizationId = uuid("organization_id")
        .references(OrganizationsTable.id, onDelete = ReferenceOption.CASCADE)
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val role = text("role")
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(organizationId, userId)

    init {
        index(false, userId)
    }
}

object OrganizationInvitationsTable : Table("organization_invitations") {
    val id = uuid("id")
    val organizationId = uuid("organization_id")
        .references(OrganizationsTable.id, onDelete = ReferenceOption.CASCADE)
    val inviterId = uuid("inviter_id")
        .references(UsersTable.id, onDelete = ReferenceOption.SET_NULL)
        .nullable()
    val inviteeUserId = uuid("invitee_user_id")
        .references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
        .nullable()
    val email = text("email").nullable()
    val role = text("role")
    val status = text("status").default("pending")
    val tokenHash = text("token_hash").nullable().uniqueIndex()
    val expiresAt = instant("expires_at")
    val createdAt = instant("created_at")
    val respondedAt = instant("responded_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, organizationId, status)
        index(false, inviteeUserId, status)
        index(false, email, status)
    }
}

object OrganizationTeamsTable : Table("organization_teams") {
    val id = uuid("id")
    val organizationId = uuid("organization_id")
        .references(OrganizationsTable.id, onDelete = ReferenceOption.CASCADE)
    val slug = text("slug")
    val name = text("name")
    val description = text("description").nullable()
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(organizationId, slug)
        index(false, organizationId)
    }
}

object OrganizationTeamMembersTable : Table("organization_team_members") {
    val teamId = uuid("team_id")
        .references(OrganizationTeamsTable.id, onDelete = ReferenceOption.CASCADE)
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val role = text("role").default("member")
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(teamId, userId)

    init {
        index(false, userId)
    }
}

object CalendarTeamGrantsTable : Table("calendar_team_grants") {
    val calendarId = uuid("calendar_id")
        .references(CalendarsTable.id, onDelete = ReferenceOption.CASCADE)
    val teamId = uuid("team_id")
        .references(OrganizationTeamsTable.id, onDelete = ReferenceOption.CASCADE)
    val permission = text("permission").default("read")
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(calendarId, teamId)

    init {
        index(false, teamId)
    }
}

object UserGroupsTable : Table("user_groups") {
    val id = uuid("id")
    val name = text("name").uniqueIndex()
    val isSystem = bool("is_system").default(false)
    val storageQuotaBytes = long("storage_quota_bytes").nullable()
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(id)
}

object UserGroupMembersTable : Table("user_group_members") {
    val groupId = uuid("group_id").references(UserGroupsTable.id, onDelete = ReferenceOption.CASCADE)
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val addedBy = uuid("added_by")
        .references(UsersTable.id, onDelete = ReferenceOption.SET_NULL)
        .nullable()
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(groupId, userId)

    init {
        index(false, userId)
    }
}

object SessionsTable : Table("sessions") {
    val id = uuid("id")
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val tokenHash = text("token_hash").uniqueIndex()
    val expiresAt = instant("expires_at")
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, userId)
        index(false, expiresAt)
    }
}

object CalendarsTable : Table("calendars") {
    val id = uuid("id")
    val ownerId = uuid("owner_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val organizationId = uuid("organization_id")
        .references(OrganizationsTable.id, onDelete = ReferenceOption.CASCADE)
        .nullable()
    val displayName = text("display_name")
    val description = text("description").nullable()
    val timeZone = text("time_zone")
    val color = text("color")
    val publicLinkEnabled = bool("public_link_enabled").default(false)
    val publicLinkToken = text("public_link_token").nullable().uniqueIndex()
    val requestsEnabled = bool("requests_enabled").default(false)
    val slotMinutes = integer("slot_minutes").default(60)
    val accessMode = text("access_mode").default("inherit")
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, ownerId)
        index(false, organizationId)
    }
}

object CalendarSharesTable : Table("calendar_shares") {
    val calendarId = uuid("calendar_id").references(CalendarsTable.id, onDelete = ReferenceOption.CASCADE)
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val permission = text("permission").default("read")
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(calendarId, userId)

    init {
        index(false, userId)
    }
}

object CalendarFollowersTable : Table("calendar_followers") {
    val calendarId = uuid("calendar_id").references(CalendarsTable.id, onDelete = ReferenceOption.CASCADE)
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(calendarId, userId)

    init {
        index(false, userId)
    }
}

object EventsTable : Table("events") {
    val id = uuid("id")
    val calendarId = uuid("calendar_id")
        .references(CalendarsTable.id, onDelete = ReferenceOption.CASCADE)
    val title = text("title")
    val description = text("description").nullable()
    val location = text("location").nullable()
    val url = text("url").nullable()
    val startAt = instant("start_at")
    val endAt = instant("end_at")
    val allDay = bool("all_day")
    val timeZone = text("time_zone").nullable()
    val status = text("status")
    val recurrenceFrequency = text("recurrence_frequency").nullable()
    val recurrenceInterval = integer("recurrence_interval").nullable()
    val recurrenceUntil = instant("recurrence_until").nullable()
    val recurrenceCount = integer("recurrence_count").nullable()
    val etag = text("etag")
    val openRsvp = bool("open_rsvp").default(false)
    val externalCalendarId = uuid("external_calendar_id")
        .references(ExternalCalendarsTable.id, onDelete = ReferenceOption.SET_NULL)
        .nullable()
    val externalUid = text("external_uid").nullable()
    val externalEtag = text("external_etag").nullable()
    val externalUpdatedAt = instant("external_updated_at").nullable()
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, calendarId, startAt, endAt)
        uniqueIndex(externalCalendarId, externalUid)
    }
}

object CalendarConnectionsTable : Table("calendar_connections") {
    val id = uuid("id")
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val provider = text("provider")
    val externalAccountId = text("external_account_id")
    val accountEmail = text("account_email").nullable()
    val displayName = text("display_name").nullable()
    val accessTokenCiphertext = text("access_token_ciphertext")
    val accessTokenNonce = text("access_token_nonce")
    val refreshTokenCiphertext = text("refresh_token_ciphertext").nullable()
    val refreshTokenNonce = text("refresh_token_nonce").nullable()
    val tokenKeyVersion = integer("token_key_version").default(1)
    val tokenExpiresAt = instant("token_expires_at").nullable()
    val scopes = text("scopes").nullable()
    val status = text("status").default("active")
    val lastSyncAt = instant("last_sync_at").nullable()
    val lastError = text("last_error").nullable()
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(userId, provider, externalAccountId)
        index(false, userId)
    }
}

object ExternalCalendarsTable : Table("external_calendars") {
    val id = uuid("id")
    val connectionId = uuid("connection_id")
        .references(CalendarConnectionsTable.id, onDelete = ReferenceOption.CASCADE)
    val externalId = text("external_id")
    val calendarId = uuid("calendar_id")
        .references(CalendarsTable.id, onDelete = ReferenceOption.CASCADE)
        .uniqueIndex()
    val externalName = text("external_name").nullable()
    val syncDirection = text("sync_direction").default("pull")
    val enabled = bool("enabled").default(true)
    val syncToken = text("sync_token").nullable()
    val lastSyncAt = instant("last_sync_at").nullable()
    val lastError = text("last_error").nullable()
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(connectionId, externalId)
        index(false, connectionId)
    }
}

object DiscordEventRoutesTable : Table("discord_event_routes") {
    val id = uuid("id")
    val externalCalendarId = uuid("external_calendar_id")
        .references(ExternalCalendarsTable.id, onDelete = ReferenceOption.CASCADE)
    val eventId = text("event_id")
    val calendarId = uuid("calendar_id")
        .references(CalendarsTable.id, onDelete = ReferenceOption.CASCADE)
        .nullable()
    val skipped = bool("skipped").default(false)
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(externalCalendarId, eventId)
        index(false, calendarId)
    }
}

object OAuthStatesTable : Table("oauth_states") {
    val state = text("state")
    val userId = uuid("user_id")
        .references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
        .nullable()
    val provider = text("provider")
    val codeVerifierCiphertext = text("code_verifier_ciphertext")
    val codeVerifierNonce = text("code_verifier_nonce")
    val redirectUri = text("redirect_uri")
    val returnTo = text("return_to").nullable()
    val createdAt = instant("created_at")
    val expiresAt = instant("expires_at")
    val usedAt = instant("used_at").nullable()

    override val primaryKey = PrimaryKey(state)

    init {
        index(false, expiresAt)
    }
}

object ExternalEventTombstonesTable : Table("external_event_tombstones") {
    val id = uuid("id")
    val externalCalendarId = uuid("external_calendar_id")
        .references(ExternalCalendarsTable.id, onDelete = ReferenceOption.CASCADE)
    val externalUid = text("external_uid")
    val deletedAt = instant("deleted_at")
    val uploadedAt = instant("uploaded_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(externalCalendarId, externalUid)
        index(false, externalCalendarId, uploadedAt)
    }
}

object EventAttendeesTable : Table("event_attendees") {
    val id = uuid("id")
    val eventId = uuid("event_id").references(EventsTable.id, onDelete = ReferenceOption.CASCADE)
    val userId = uuid("user_id")
        .references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
        .nullable()
    val email = text("email").nullable()
    val name = text("name").nullable()
    val status = text("status").default("invited")
    val invitedBy = uuid("invited_by")
        .references(UsersTable.id, onDelete = ReferenceOption.SET_NULL)
        .nullable()
    val tokenHash = text("token_hash").nullable()
    val createdAt = instant("created_at")
    val respondedAt = instant("responded_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(eventId, userId)
        uniqueIndex(tokenHash)
        index(false, userId, status)
    }
}

object CalendarHiddenTable : Table("calendar_hidden") {
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val calendarId = uuid("calendar_id").references(CalendarsTable.id, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(userId, calendarId)
}

object HolidaySubscriptionsTable : Table("holiday_subscriptions") {
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val holidayId = text("holiday_id")

    override val primaryKey = PrimaryKey(userId, holidayId)
}

object AppSettingsTable : Table("app_settings") {
    val key = text("key")
    val value = text("value")

    override val primaryKey = PrimaryKey(key)
}
object CustomHolidaysTable : Table("custom_holidays") {
    val id = uuid("id")
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val title = text("title")
    val month = integer("month")
    val day = integer("day")
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, userId)
    }
}

object EmailTokensTable : Table("email_tokens") {
    val id = uuid("id")
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val purpose = text("purpose")
    val tokenHash = text("token_hash").uniqueIndex()
    val email = text("email").nullable()
    val expiresAt = instant("expires_at")
    val usedAt = instant("used_at").nullable()
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, userId, purpose)
    }
}

object LoginDevicesTable : Table("login_devices") {
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val fingerprint = text("fingerprint")
    val userAgent = text("user_agent").nullable()
    val ip = text("ip").nullable()
    val firstSeenAt = instant("first_seen_at")
    val lastSeenAt = instant("last_seen_at")

    override val primaryKey = PrimaryKey(userId, fingerprint)
}

object NotificationsTable : Table("notifications") {
    val id = uuid("id")
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val kind = text("kind")
    val title = text("title")
    val body = text("body").nullable()
    val href = text("href").nullable()
    val readAt = instant("read_at").nullable()
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, userId, createdAt)
    }
}

object UserReminderDefaultsTable : Table("user_reminder_defaults") {
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val offsetSeconds = integer("offset_seconds")

    override val primaryKey = PrimaryKey(userId, offsetSeconds)
}

object EventReminderSettingsTable : Table("event_reminder_settings") {
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val eventId = uuid("event_id").references(EventsTable.id, onDelete = ReferenceOption.CASCADE)
    val useDefaults = bool("use_defaults").default(true)
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(userId, eventId)
}

object EventRemindersTable : Table("event_reminders") {
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val eventId = uuid("event_id").references(EventsTable.id, onDelete = ReferenceOption.CASCADE)
    val offsetSeconds = integer("offset_seconds")
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(userId, eventId, offsetSeconds)
}

object CalendarAvailabilityTable : Table("calendar_availability") {
    val id = uuid("id")
    val calendarId = uuid("calendar_id").references(CalendarsTable.id, onDelete = ReferenceOption.CASCADE)
    val weekday = integer("weekday")
    val startMinute = integer("start_minute")
    val endMinute = integer("end_minute")
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, calendarId, weekday)
    }
}

object TimeSlotRequestsTable : Table("time_slot_requests") {
    val id = uuid("id")
    val calendarId = uuid("calendar_id").references(CalendarsTable.id, onDelete = ReferenceOption.CASCADE)
    val requesterId = uuid("requester_id")
        .references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
        .nullable()
    val requesterName = text("requester_name").nullable()
    val requesterEmail = text("requester_email").nullable()
    val startAt = instant("start_at")
    val endAt = instant("end_at")
    val message = text("message").nullable()
    val status = text("status")
    val createdAt = instant("created_at")
    val respondedAt = instant("responded_at").nullable()
    val respondedBy = uuid("responded_by")
        .references(UsersTable.id, onDelete = ReferenceOption.SET_NULL)
        .nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, calendarId, createdAt)
        index(false, requesterId, createdAt)
    }
}

object FriendshipsTable : Table("friendships") {
    val id = uuid("id")
    val requesterId = uuid("requester_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val addresseeId = uuid("addressee_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val status = text("status")
    val createdAt = instant("created_at")
    val respondedAt = instant("responded_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(requesterId, addresseeId)
        index(false, addresseeId, status)
        index(false, requesterId, status)
    }
}
