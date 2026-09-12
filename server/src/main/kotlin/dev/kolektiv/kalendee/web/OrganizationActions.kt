package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.PublicAccessMode
import dev.kolektiv.kalendee.auth.SessionTokens
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.auth.toUuid
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.OrganizationId
import dev.kolektiv.kalendee.calendar.OrganizationVisibility
import dev.kolektiv.kalendee.db.CalendarsTable
import dev.kolektiv.kalendee.db.OrganizationInvitationsTable
import dev.kolektiv.kalendee.db.OrganizationMembersTable
import dev.kolektiv.kalendee.db.OrganizationsTable
import dev.kolektiv.kalendee.db.UsersTable
import dev.kolektiv.kalendee.organizations.Organization
import dev.kolektiv.kalendee.organizations.OrganizationInvitation
import dev.kolektiv.kalendee.organizations.OrganizationMember
import dev.kolektiv.kalendee.organizations.OrganizationRole
import dev.kolektiv.kalendee.organizations.OrganizationService
import dev.kolektiv.keel.KeelAction
import dev.kolektiv.keel.ktor.PageMissingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

class OrganizationActions(
    private val orgs: OrganizationService,
    private val auth: AuthService,
    private val settings: AuthSettings,
    private val database: Database,
) {
    @KeelAction("kalendee.organizations")
    suspend fun organizations(input: OrganizationsIn): OrganizationsOut = mapDomainErrors("organizationId") {
        val user = requireSessionUser(auth, settings)
        OrganizationsOut(
            organizations = summaries(orgs.listFor(user.id).map { it.organization }, user.id),
        )
    }

    @KeelAction("kalendee.createOrganization")
    suspend fun createOrganization(input: CreateOrganizationIn): OrganizationSummary = mapDomainErrors("slug") {
        val user = requireSessionUser(auth, settings)
        val org = orgs.create(user.id, input.slug, input.displayName, input.description)
        org.toSummary(role = OrganizationRole.OWNER.wire, memberCount = 1)
    }

    @KeelAction("kalendee.updateOrganization")
    suspend fun updateOrganization(input: UpdateOrganizationIn): OrganizationSummary =
        mapDomainErrors("organizationId") {
            val user = requireSessionUser(auth, settings)
            val orgId = OrganizationId.parse(input.organizationId)
            val visibility = input.visibility
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(OrganizationVisibility::parse)
            val updated = orgs.update(user.id, orgId, input.displayName, input.description, visibility)
            summary(updated, user.id)
        }

    @KeelAction("kalendee.deleteOrganization")
    suspend fun deleteOrganization(input: DeleteOrganizationIn): DeletedOut = mapDomainErrors("organizationId") {
        val user = requireSessionUser(auth, settings)
        orgs.delete(user.id, OrganizationId.parse(input.organizationId))
        DeletedOut()
    }

    @KeelAction("kalendee.organizationMembers")
    suspend fun organizationMembers(input: OrganizationMembersIn): OrganizationMembersOut =
        mapDomainErrors("organizationId") {
            val user = requireSessionUser(auth, settings)
            val orgId = OrganizationId.parse(input.organizationId)
            val role = orgs.role(orgId, user.id)
            OrganizationMembersOut(
                organizationId = orgId.value,
                viewerRole = role?.wire,
                canManageMembers = role != null && role != OrganizationRole.MEMBER,
                canManageOwners = role == OrganizationRole.OWNER,
                members = orgs.members(user.id, orgId).map { it.toSummary(user.id) },
            )
        }

    @KeelAction("kalendee.organizationInvitations")
    suspend fun organizationInvitations(input: OrganizationInvitationsIn): OrganizationInvitationsOut =
        mapDomainErrors("organizationId") {
            val user = requireSessionUser(auth, settings)
            val orgId = OrganizationId.parse(input.organizationId)
            OrganizationInvitationsOut(
                organizationId = orgId.value,
                invitations = invitationSummaries(orgs.invitations(user.id, orgId)),
            )
        }

    @KeelAction("kalendee.inviteToOrganization")
    suspend fun inviteToOrganization(input: InviteToOrganizationIn): OrganizationInvitationOut =
        mapDomainErrors("identifier") {
            val user = requireSessionUser(auth, settings)
            val result = orgs.invite(
                user.id,
                OrganizationId.parse(input.organizationId),
                input.identifier,
                OrganizationRole.parse(input.role),
            )
            OrganizationInvitationOut(id = result.invitationId.toString(), status = result.status)
        }

    @KeelAction("kalendee.revokeOrganizationInvitation")
    suspend fun revokeOrganizationInvitation(input: OrganizationInvitationIn): OrganizationInvitationOut =
        mapDomainErrors("invitationId") {
            val user = requireSessionUser(auth, settings)
            val revoked = orgs.revokeInvitation(user.id, input.invitationId)
            OrganizationInvitationOut(id = revoked.id.toString(), status = revoked.status)
        }

    @KeelAction("kalendee.setOrganizationMemberRole")
    suspend fun setOrganizationMemberRole(input: SetOrganizationMemberRoleIn): OrganizationMemberSummary =
        mapDomainErrors("role") {
            val user = requireSessionUser(auth, settings)
            val updated = orgs.updateMemberRole(
                user.id,
                OrganizationId.parse(input.organizationId),
                UserId.parse(input.userId),
                OrganizationRole.parse(input.role),
            )
            updated.toSummary(user.id)
        }

    @KeelAction("kalendee.removeOrganizationMember")
    suspend fun removeOrganizationMember(input: RemoveOrganizationMemberIn): DeletedOut =
        mapDomainErrors("userId") {
            val user = requireSessionUser(auth, settings)
            orgs.removeMember(user.id, OrganizationId.parse(input.organizationId), UserId.parse(input.userId))
            DeletedOut()
        }

    @KeelAction("kalendee.acceptOrganizationInvitation")
    suspend fun acceptOrganizationInvitation(input: RespondOrganizationInvitationIn): OrganizationMembershipOut =
        mapDomainErrors("invitationId") {
            val user = requireSessionUser(auth, settings)
            val membership = when {
                !input.invitationId.isNullOrBlank() -> orgs.acceptInvitation(user.id, input.invitationId)
                !input.token.isNullOrBlank() -> orgs.acceptInvitationByToken(user.id, input.token)
                else -> throw CalendarException.Invalid("invitation id or token is required")
            }
            OrganizationMembershipOut(
                organization = summary(membership.organization, user.id).copy(role = membership.role.wire),
                role = membership.role.wire,
            )
        }

    @KeelAction("kalendee.declineOrganizationInvitation")
    suspend fun declineOrganizationInvitation(input: RespondOrganizationInvitationIn): DeletedOut =
        mapDomainErrors("invitationId") {
            val user = requireSessionUser(auth, settings)
            val invitationId = input.invitationId?.takeIf { it.isNotBlank() }
                ?: throw CalendarException.Invalid("invitation id is required")
            orgs.declineInvitation(user.id, invitationId)
            DeletedOut()
        }

    suspend fun summary(org: Organization, viewerId: UserId?): OrganizationSummary =
        org.toSummary(
            role = viewerId?.let { orgs.role(org.id, it)?.wire },
            memberCount = memberCounts(listOf(org.id))[org.id] ?: 0,
        )

    suspend fun summaries(organizations: List<Organization>, viewerId: UserId?): List<OrganizationSummary> {
        if (organizations.isEmpty()) return emptyList()
        val ids = organizations.map { it.id }
        val counts = memberCounts(ids)
        val roles = viewerId?.let { membershipRoles(it, ids) }.orEmpty()
        return organizations.map { org ->
            org.toSummary(role = roles[org.id]?.wire, memberCount = counts[org.id] ?: 0)
        }
    }

    suspend fun summariesById(
        orgIds: Collection<OrganizationId>,
        viewerId: UserId?,
    ): Map<OrganizationId, OrganizationSummary> {
        val ids = orgIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val organizations = dbQuery {
            OrganizationsTable.selectAll()
                .where { OrganizationsTable.id inList ids.map { it.toUuid() } }
                .map { it.toOrganizationRow() }
        }
        return summaries(organizations, viewerId).associateBy { OrganizationId(it.id) }
    }

    suspend fun summaryById(orgId: OrganizationId, viewerId: UserId?): OrganizationSummary? {
        val org = orgs.byId(orgId) ?: return null
        return summary(org, viewerId)
    }

    suspend fun organizationProfilePage(
        slug: String,
        viewer: User?,
        inviteToken: String?,
    ): OrganizationProfilePage {
        val org = orgs.bySlug(slug) ?: throw PageMissingException("/o/$slug")
        val viewerRole = viewer?.let { orgs.role(org.id, it.id) }
        val token = inviteToken?.trim()?.takeIf { it.isNotEmpty() }
        val invitation = pendingInvitation(org.id, viewer, token)
        val instanceMode = instancePublicAccess()
        val allowed = when {
            viewerRole != null -> true
            org.visibility == OrganizationVisibility.PUBLIC ->
                viewer != null || instanceMode == PublicAccessMode.PUBLIC
            else -> viewer != null && invitation != null
        }
        if (!allowed) throw PageMissingException("/o/$slug")
        val canViewMembers = org.visibility == OrganizationVisibility.PUBLIC || viewerRole != null
        val members = if (canViewMembers) membersOf(org.id).map { it.toSummary(viewer?.id) } else emptyList()
        val orgSummary = org.toSummary(
            role = viewerRole?.wire,
            memberCount = memberCounts(listOf(org.id))[org.id] ?: 0,
        )
        val calendars = calendarsOf(org.id)
            .filter { calendarVisible(it, viewer) }
            .map { it.toSummary(auth, organization = orgSummary) }
        return OrganizationProfilePage(
            org = orgSummary,
            viewer = viewer?.toViewer(),
            viewerRole = viewerRole?.wire,
            pendingInvitation = invitation?.let { invitationSummary(it) },
            inviteToken = if (viewerRole == null) token else null,
            members = members,
            calendars = calendars,
            canManageSettings = viewerRole == OrganizationRole.OWNER || viewerRole == OrganizationRole.ADMIN,
        )
    }

    suspend fun organizationSettingsPage(slug: String, viewer: User): OrganizationSettingsPage {
        val org = orgs.bySlug(slug) ?: throw PageMissingException("/o/$slug/settings")
        val role = orgs.role(org.id, viewer.id) ?: throw PageMissingException("/o/$slug/settings")
        val canManageMembers = role != OrganizationRole.MEMBER
        val members = orgs.members(viewer.id, org.id).map { it.toSummary(viewer.id) }
        val invitations = if (canManageMembers) {
            invitationSummaries(orgs.invitations(viewer.id, org.id))
        } else {
            emptyList()
        }
        return OrganizationSettingsPage(
            org = org.toSummary(role = role.wire, memberCount = members.size),
            viewer = viewer.toViewer(),
            viewerRole = role.wire,
            members = members,
            invitations = invitations,
            canManageMembers = canManageMembers,
            canManageOwners = role == OrganizationRole.OWNER,
        )
    }

    suspend fun publicProfilePage(username: String, viewer: User?): PublicProfilePage {
        val normalized = username.trim().lowercase()
        val profileUser = auth.userByIdentifier(normalized)?.takeIf { it.username == normalized }
            ?: throw PageMissingException("/u/$username")
        val isSelf = viewer?.id == profileUser.id
        if (!isSelf && viewer?.admin != true) {
            val resolved = resolveUserAccess(profileUser, instancePublicAccess())
            if (viewer == null && resolved != PublicAccessMode.PUBLIC) {
                throw PageMissingException("/u/$username")
            }
        }
        val calendars = ownedPublicCalendars(profileUser.id).filter { calendarVisible(it, viewer) }
        val organizations = publicOrganizationsFor(profileUser.id)
        val orgSummaries = summariesById(calendars.mapNotNull { it.organizationId }, viewer?.id)
        return PublicProfilePage(
            username = profileUser.username,
            displayName = profileUser.displayName,
            avatarUrl = profileUser.avatarVersion?.let { avatarUrl(profileUser.id, it) },
            isSelf = isSelf,
            calendars = calendars.map {
                it.toSummary(auth, organization = it.organizationId?.let(orgSummaries::get))
            },
            organizations = summaries(organizations, viewer?.id),
            viewer = viewer?.toViewer(),
        )
    }

    suspend fun directoryPage(viewer: User?): PublicDirectoryPage {
        val instanceMode = instancePublicAccess()
        if (viewer == null && instanceMode != PublicAccessMode.PUBLIC) {
            throw PageMissingException("/directory")
        }
        val allUsers = auth.listUsers()
        val usersById = allUsers.associateBy { it.id }
        val organizationsById = organizationsById()
        val counts = memberCounts(organizationsById.keys)
        val roles = viewer?.let { membershipRoles(it.id, organizationsById.keys) }.orEmpty()
        val publicOrgs = organizationsById.values
            .filter { it.visibility == OrganizationVisibility.PUBLIC }
            .sortedBy { it.displayName.lowercase() }
        val users = allUsers.filter { userVisible(it, viewer, instanceMode) }
        val calendars = allPublicCalendars().filter { calendar ->
            val organization = calendar.organizationId?.let(organizationsById::get)
            if (organization?.visibility == OrganizationVisibility.PRIVATE) {
                false
            } else {
                val ownerAccess = usersById[calendar.ownerId]?.publicAccess
                val mode = when {
                    calendar.accessMode != PublicAccessMode.INHERIT -> calendar.accessMode
                    ownerAccess != null && ownerAccess != PublicAccessMode.INHERIT -> ownerAccess
                    else -> instanceMode
                }
                directoryVisible(mode, viewer)
            }
        }
        return PublicDirectoryPage(
            viewer = viewer?.toViewer(),
            publicAccess = instanceMode.wire,
            orgs = publicOrgs.map { org ->
                DirectoryOrgSummary(
                    id = org.id.value,
                    slug = org.slug,
                    displayName = org.displayName,
                    description = org.description,
                    avatarUrl = null,
                    memberCount = counts[org.id] ?: 0,
                    viewerRole = roles[org.id]?.wire,
                )
            },
            users = users.map { user ->
                DirectoryUserSummary(
                    userId = user.id.value,
                    username = user.username,
                    displayName = user.displayName,
                    avatarUrl = user.avatarVersion?.let { avatarUrl(user.id, it) },
                )
            },
            calendars = calendars.map { calendar ->
                val organization = calendar.organizationId?.let(organizationsById::get)
                DirectoryCalendarSummary(
                    id = calendar.id.value,
                    displayName = calendar.displayName,
                    token = calendar.publicLinkToken.orEmpty(),
                    ownerName = organization?.displayName
                        ?: usersById[calendar.ownerId]?.displayName.orEmpty(),
                    ownerUsername = usersById[calendar.ownerId]?.username,
                    organizationName = organization?.displayName,
                    color = calendar.color,
                )
            },
        )
    }

    suspend fun calendarVisible(calendar: Calendar, viewer: User?): Boolean {
        if (!calendar.publicLinkEnabled) return false
        val mode = auth.effectivePublicAccess(calendar)
        if (viewer == null) return mode == PublicAccessMode.PUBLIC
        return auth.canViewPublic(calendar, viewer.id)
    }

    private fun directoryVisible(mode: PublicAccessMode, viewer: User?): Boolean =
        if (viewer == null) mode == PublicAccessMode.PUBLIC else true

    private fun resolveUserAccess(user: User, instanceMode: PublicAccessMode): PublicAccessMode =
        if (user.publicAccess != PublicAccessMode.INHERIT) user.publicAccess else instanceMode

    private fun userVisible(user: User, viewer: User?, instanceMode: PublicAccessMode): Boolean {
        val resolved = resolveUserAccess(user, instanceMode)
        return if (viewer == null) resolved == PublicAccessMode.PUBLIC else true
    }

    private suspend fun instancePublicAccess(): PublicAccessMode =
        PublicAccessMode.fromWire(auth.publicAccess()) ?: PublicAccessMode.PUBLIC

    private suspend fun pendingInvitation(
        orgId: OrganizationId,
        viewer: User?,
        token: String?,
    ): OrganizationInvitation? = dbQuery {
        if (token != null) {
            OrganizationInvitationsTable.selectAll()
                .where {
                    (OrganizationInvitationsTable.organizationId eq orgId.toUuid()) and
                        (OrganizationInvitationsTable.status eq OrganizationInvitation.PENDING) and
                        (OrganizationInvitationsTable.tokenHash eq SessionTokens.hash(token))
                }
                .singleOrNull()
                ?.toOrganizationInvitation()
        } else if (viewer != null) {
            val byUser = OrganizationInvitationsTable.inviteeUserId eq viewer.id.toUuid()
            val target = viewer.email?.let { email ->
                byUser or (OrganizationInvitationsTable.email eq email)
            } ?: byUser
            OrganizationInvitationsTable.selectAll()
                .where {
                    (OrganizationInvitationsTable.organizationId eq orgId.toUuid()) and
                        (OrganizationInvitationsTable.status eq OrganizationInvitation.PENDING) and
                        target
                }
                .orderBy(OrganizationInvitationsTable.createdAt to SortOrder.DESC)
                .firstOrNull()
                ?.toOrganizationInvitation()
        } else {
            null
        }
    }

    private suspend fun invitationSummary(invitation: OrganizationInvitation): OrganizationInvitationSummary =
        invitation.toSummary(invitation.resolvedInvitee())

    private suspend fun invitationSummaries(
        invitations: List<OrganizationInvitation>,
    ): List<OrganizationInvitationSummary> =
        invitations.map { it.toSummary(it.resolvedInvitee()) }

    private suspend fun OrganizationInvitation.resolvedInvitee(): User? =
        inviteeUserId?.let { auth.userById(it) } ?: email?.let { auth.userByIdentifier(it) }

    private suspend fun membersOf(orgId: OrganizationId): List<OrganizationMember> = dbQuery {
        (OrganizationMembersTable innerJoin UsersTable)
            .selectAll()
            .where { OrganizationMembersTable.organizationId eq orgId.toUuid() }
            .orderBy(UsersTable.username to SortOrder.ASC)
            .map { it.toOrganizationMember(orgId) }
    }

    private suspend fun calendarsOf(orgId: OrganizationId): List<Calendar> = dbQuery {
        (CalendarsTable innerJoin UsersTable)
            .selectAll()
            .where {
                (CalendarsTable.organizationId eq orgId.toUuid()) and
                    (CalendarsTable.publicLinkEnabled eq true)
            }
            .orderBy(CalendarsTable.displayName to SortOrder.ASC, CalendarsTable.id to SortOrder.ASC)
            .map { it.toPublicCalendar() }
    }

    private suspend fun ownedPublicCalendars(userId: UserId): List<Calendar> = dbQuery {
        (CalendarsTable innerJoin UsersTable)
            .selectAll()
            .where {
                (CalendarsTable.ownerId eq userId.toUuid()) and
                    (CalendarsTable.publicLinkEnabled eq true)
            }
            .orderBy(CalendarsTable.displayName to SortOrder.ASC, CalendarsTable.id to SortOrder.ASC)
            .map { it.toPublicCalendar() }
    }

    private suspend fun allPublicCalendars(): List<Calendar> = dbQuery {
        (CalendarsTable innerJoin UsersTable)
            .selectAll()
            .where { CalendarsTable.publicLinkEnabled eq true }
            .orderBy(CalendarsTable.displayName to SortOrder.ASC, CalendarsTable.id to SortOrder.ASC)
            .map { it.toPublicCalendar() }
    }

    private suspend fun publicOrganizationsFor(userId: UserId): List<Organization> = dbQuery {
        (OrganizationMembersTable innerJoin OrganizationsTable)
            .selectAll()
            .where {
                (OrganizationMembersTable.userId eq userId.toUuid()) and
                    (OrganizationsTable.visibility eq OrganizationVisibility.PUBLIC.wire)
            }
            .orderBy(OrganizationsTable.displayName to SortOrder.ASC)
            .map { it.toOrganizationRow() }
    }

    private suspend fun organizationsById(): Map<OrganizationId, Organization> = dbQuery {
        OrganizationsTable.selectAll()
            .orderBy(OrganizationsTable.displayName to SortOrder.ASC)
            .map { it.toOrganizationRow() }
            .associateBy { it.id }
    }

    private suspend fun memberCounts(orgIds: Collection<OrganizationId>): Map<OrganizationId, Int> = dbQuery {
        val ids = orgIds.distinct()
        if (ids.isEmpty()) return@dbQuery emptyMap()
        OrganizationMembersTable.selectAll()
            .where { OrganizationMembersTable.organizationId inList ids.map { it.toUuid() } }
            .map { OrganizationId(it[OrganizationMembersTable.organizationId].toString()) }
            .groupingBy { it }
            .eachCount()
    }

    private suspend fun membershipRoles(
        userId: UserId,
        orgIds: Collection<OrganizationId>,
    ): Map<OrganizationId, OrganizationRole> = dbQuery {
        val ids = orgIds.distinct()
        if (ids.isEmpty()) return@dbQuery emptyMap()
        OrganizationMembersTable.selectAll()
            .where {
                (OrganizationMembersTable.userId eq userId.toUuid()) and
                    (OrganizationMembersTable.organizationId inList ids.map { it.toUuid() })
            }
            .associate { row ->
                OrganizationId(row[OrganizationMembersTable.organizationId].toString()) to
                    (OrganizationRole.fromWire(row[OrganizationMembersTable.role]) ?: OrganizationRole.MEMBER)
            }
    }

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }
}

private fun ResultRow.toOrganizationRow(): Organization = Organization(
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

private fun ResultRow.toOrganizationMember(orgId: OrganizationId): OrganizationMember = OrganizationMember(
    organizationId = orgId,
    userId = UserId(this[OrganizationMembersTable.userId].toString()),
    username = this[UsersTable.username],
    displayName = this[UsersTable.displayName],
    avatarVersion = this[UsersTable.avatarUpdatedAt]?.toEpochMilliseconds(),
    role = OrganizationRole.fromWire(this[OrganizationMembersTable.role]) ?: OrganizationRole.MEMBER,
    createdAt = this[OrganizationMembersTable.createdAt],
)

private fun ResultRow.toOrganizationInvitation(): OrganizationInvitation = OrganizationInvitation(
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

private fun ResultRow.toPublicCalendar(): Calendar = Calendar(
    id = CalendarId(this[CalendarsTable.id].toString()),
    ownerId = UserId(this[CalendarsTable.ownerId].toString()),
    displayName = this[CalendarsTable.displayName],
    description = this[CalendarsTable.description],
    timeZone = this[CalendarsTable.timeZone],
    color = this[CalendarsTable.color],
    hidden = false,
    permission = CalendarPermission.READ,
    publicLinkEnabled = this[CalendarsTable.publicLinkEnabled],
    publicLinkToken = this[CalendarsTable.publicLinkToken],
    requestsEnabled = this[CalendarsTable.requestsEnabled],
    slotMinutes = this[CalendarsTable.slotMinutes],
    accessMode = PublicAccessMode.fromWire(this[CalendarsTable.accessMode]) ?: PublicAccessMode.INHERIT,
    createdAt = this[CalendarsTable.createdAt],
    updatedAt = this[CalendarsTable.updatedAt],
    ownerAvatarVersion = this[UsersTable.avatarUpdatedAt]?.toEpochMilliseconds(),
    organizationId = this[CalendarsTable.organizationId]?.let { OrganizationId(it.toString()) },
)
