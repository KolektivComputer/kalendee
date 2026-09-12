package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.OrganizationId
import dev.kolektiv.kalendee.calendar.OrganizationTeamId
import dev.kolektiv.kalendee.organizations.DefaultTeamSlug
import dev.kolektiv.kalendee.organizations.OrganizationRole
import dev.kolektiv.kalendee.organizations.OrganizationService
import dev.kolektiv.kalendee.organizations.OrganizationTeam
import dev.kolektiv.kalendee.organizations.OrganizationTeamRole
import dev.kolektiv.kalendee.organizations.OrganizationTeamService
import dev.kolektiv.keel.KeelAction

class OrganizationTeamActions(
    private val teams: OrganizationTeamService,
    private val orgs: OrganizationService,
    private val store: CalendarStore,
    private val auth: AuthService,
    private val settings: AuthSettings,
) {
    @KeelAction("kalendee.organizationTeams")
    suspend fun organizationTeams(input: OrganizationTeamsIn): OrganizationTeamsOut =
        mapDomainErrors("organizationId") {
            val user = requireSessionUser(auth, settings)
            val orgId = OrganizationId.parse(input.organizationId)
            val role = requireRole(user, orgId)
            OrganizationTeamsOut(
                organizationId = orgId.value,
                viewerRole = role.wire,
                canManageTeams = role != OrganizationRole.MEMBER,
                teams = teamSummaries(user, orgId, role),
                manageableCalendars = manageableCalendars(user.id, orgId),
            )
        }

    @KeelAction("kalendee.createOrganizationTeam")
    suspend fun createOrganizationTeam(input: CreateOrganizationTeamIn): OrganizationTeamSummary =
        mapDomainErrors("slug") {
            val user = requireSessionUser(auth, settings)
            val orgId = OrganizationId.parse(input.organizationId)
            val role = requireRole(user, orgId)
            val team = teams.create(user.id, orgId, input.slug, input.name, input.description)
            teamSummary(user, team, role)
        }

    @KeelAction("kalendee.updateOrganizationTeam")
    suspend fun updateOrganizationTeam(input: UpdateOrganizationTeamIn): OrganizationTeamSummary =
        mapDomainErrors("teamId") {
            val user = requireSessionUser(auth, settings)
            val updated = teams.update(user.id, OrganizationTeamId.parse(input.teamId), input.name, input.description)
            teamSummary(user, updated, requireRole(user, updated.organizationId))
        }

    @KeelAction("kalendee.deleteOrganizationTeam")
    suspend fun deleteOrganizationTeam(input: DeleteOrganizationTeamIn): DeletedOut =
        mapDomainErrors("teamId") {
            val user = requireSessionUser(auth, settings)
            teams.delete(user.id, OrganizationTeamId.parse(input.teamId))
            DeletedOut()
        }

    @KeelAction("kalendee.addOrganizationTeamMember")
    suspend fun addOrganizationTeamMember(input: AddOrganizationTeamMemberIn): OrganizationTeamMemberSummary =
        mapDomainErrors("userId") {
            val user = requireSessionUser(auth, settings)
            teams.addMember(
                user.id,
                OrganizationTeamId.parse(input.teamId),
                UserId.parse(input.userId),
            ).toSummary(user.id)
        }

    @KeelAction("kalendee.removeOrganizationTeamMember")
    suspend fun removeOrganizationTeamMember(input: RemoveOrganizationTeamMemberIn): DeletedOut =
        mapDomainErrors("userId") {
            val user = requireSessionUser(auth, settings)
            teams.removeMember(
                user.id,
                OrganizationTeamId.parse(input.teamId),
                UserId.parse(input.userId),
            )
            DeletedOut()
        }

    @KeelAction("kalendee.setOrganizationTeamMemberRole")
    suspend fun setOrganizationTeamMemberRole(input: SetOrganizationTeamMemberRoleIn): OrganizationTeamMemberSummary =
        mapDomainErrors("role") {
            val user = requireSessionUser(auth, settings)
            teams.setMemberRole(
                user.id,
                OrganizationTeamId.parse(input.teamId),
                UserId.parse(input.userId),
                OrganizationTeamRole.parse(input.role),
            ).toSummary(user.id)
        }

    @KeelAction("kalendee.grantCalendarToTeam")
    suspend fun grantCalendarToTeam(input: GrantCalendarToTeamIn): OrganizationTeamCalendarSummary =
        mapDomainErrors("calendarId") {
            val user = requireSessionUser(auth, settings)
            teams.grant(
                user.id,
                CalendarId.parse(input.calendarId),
                OrganizationTeamId.parse(input.teamId),
                CalendarPermission.parseShare(input.permission),
            ).toSummary()
        }

    @KeelAction("kalendee.revokeCalendarFromTeam")
    suspend fun revokeCalendarFromTeam(input: RevokeCalendarFromTeamIn): DeletedOut =
        mapDomainErrors("calendarId") {
            val user = requireSessionUser(auth, settings)
            teams.revoke(
                user.id,
                CalendarId.parse(input.calendarId),
                OrganizationTeamId.parse(input.teamId),
            )
            DeletedOut()
        }

    suspend fun settingsExtras(organizationId: OrganizationId, viewer: User): SettingsExtras {
        val role = orgs.role(organizationId, viewer.id)
            ?: return SettingsExtras(emptyList(), emptyList())
        return SettingsExtras(
            teams = teamSummaries(viewer, organizationId, role),
            manageableCalendars = manageableCalendars(viewer.id, organizationId),
        )
    }

    data class SettingsExtras(
        val teams: List<OrganizationTeamSummary>,
        val manageableCalendars: List<CalendarOptionSummary>,
    )

    private suspend fun requireRole(user: User, orgId: OrganizationId): OrganizationRole =
        orgs.role(orgId, user.id)
            ?: throw CalendarException.Forbidden("not a member of this organization")

    private suspend fun teamSummaries(
        user: User,
        orgId: OrganizationId,
        role: OrganizationRole,
    ): List<OrganizationTeamSummary> = teams.teamsForSubject(user.id)
        .filter { it.team.organizationId == orgId }
        .map { teamSummary(user, it.team, role) }

    private suspend fun teamSummary(
        user: User,
        team: OrganizationTeam,
        role: OrganizationRole,
    ): OrganizationTeamSummary {
        val members = teams.members(user.id, team.id)
        val viewerRole = members.firstOrNull { it.userId == user.id }?.role
        val canManage = role != OrganizationRole.MEMBER || viewerRole == OrganizationTeamRole.MAINTAINER
        return OrganizationTeamSummary(
            id = team.id.value,
            organizationId = team.organizationId.value,
            slug = team.slug,
            name = team.name,
            description = team.description,
            isDefault = team.slug == DefaultTeamSlug,
            memberCount = members.size,
            viewerRole = viewerRole?.wire,
            canManageMembers = canManage,
            canManageGrants = canManage,
            canDelete = role != OrganizationRole.MEMBER,
            members = members.map { it.toSummary(user.id) },
            grants = teams.grants(user.id, team.id).map { it.toSummary() },
        )
    }

    private suspend fun manageableCalendars(
        userId: UserId,
        orgId: OrganizationId,
    ): List<CalendarOptionSummary> = store.listCalendars(userId)
        .filter { it.organizationId == orgId && it.permission == CalendarPermission.OWNER }
        .sortedBy { it.displayName.lowercase() }
        .map { CalendarOptionSummary(id = it.id.value, displayName = it.displayName, color = it.color) }
}
