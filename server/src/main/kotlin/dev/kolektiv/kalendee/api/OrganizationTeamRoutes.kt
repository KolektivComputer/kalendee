package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.OrganizationId
import dev.kolektiv.kalendee.organizations.DefaultTeamSlug
import dev.kolektiv.kalendee.organizations.OrganizationRole
import dev.kolektiv.kalendee.organizations.OrganizationService
import dev.kolektiv.kalendee.organizations.OrganizationTeam
import dev.kolektiv.kalendee.organizations.OrganizationTeamCalendarGrant
import dev.kolektiv.kalendee.organizations.OrganizationTeamMember
import dev.kolektiv.kalendee.organizations.OrganizationTeamRole
import dev.kolektiv.kalendee.organizations.OrganizationTeamService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.organizationTeamRoutes(
    organizations: OrganizationService,
    teams: OrganizationTeamService,
    store: CalendarStore,
) {
    route("/organizations") {
        get("/{id}/teams") {
            val user = call.user()
            val orgId = call.organizationId()
            val role = requireOrgRole(organizations, orgId, user.id)
            call.respond(
                OrganizationTeamsResponse(
                    organizationId = orgId.value,
                    viewerRole = role.wire,
                    canManageTeams = role != OrganizationRole.MEMBER,
                    teams = teamsForOrg(user, orgId, role, teams),
                    manageableCalendars = manageableCalendars(user.id, orgId, store),
                ),
            )
        }
        post("/{id}/teams") {
            val user = call.user()
            val orgId = call.organizationId()
            val role = requireOrgRole(organizations, orgId, user.id)
            val body = call.receive<CreateTeamBody>()
            val team = teams.create(user.id, orgId, body.slug, body.name, body.description)
            call.respond(HttpStatusCode.Created, teamOut(user, team, role, teams))
        }
    }
    route("/teams") {
        patch("/{id}") {
            val user = call.user()
            val body = call.receive<UpdateTeamBody>()
            val updated = teams.update(user.id, call.organizationTeamId(), body.name, body.description)
            val role = requireOrgRole(organizations, updated.organizationId, user.id)
            call.respond(teamOut(user, updated, role, teams))
        }
        delete("/{id}") {
            val user = call.user()
            teams.delete(user.id, call.organizationTeamId())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/{id}/members") {
            val user = call.user()
            val body = call.receive<AddTeamMemberBody>()
            val member = teams.addMember(user.id, call.organizationTeamId(), UserId.parse(body.userId))
            call.respond(member.toOut(user.id))
        }
        delete("/{id}/members/{userId}") {
            val user = call.user()
            teams.removeMember(user.id, call.organizationTeamId(), call.userId())
            call.respond(HttpStatusCode.NoContent)
        }
        patch("/{id}/members/{userId}") {
            val user = call.user()
            val body = call.receive<SetTeamMemberRoleBody>()
            val member = teams.setMemberRole(
                user.id,
                call.organizationTeamId(),
                call.userId(),
                OrganizationTeamRole.parse(body.role),
            )
            call.respond(member.toOut(user.id))
        }
        put("/{id}/calendars/{calendarId}") {
            val user = call.user()
            val body = call.receive<GrantTeamCalendarBody>()
            val grant = teams.grant(
                user.id,
                call.teamCalendarId(),
                call.organizationTeamId(),
                CalendarPermission.parseShare(body.permission),
            )
            call.respond(grant.toOut())
        }
        delete("/{id}/calendars/{calendarId}") {
            val user = call.user()
            teams.revoke(user.id, call.teamCalendarId(), call.organizationTeamId())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private suspend fun requireOrgRole(
    organizations: OrganizationService,
    orgId: OrganizationId,
    userId: UserId,
): OrganizationRole = organizations.role(orgId, userId)
    ?: throw CalendarException.Forbidden("not a member of this organization")

private suspend fun teamsForOrg(
    user: User,
    orgId: OrganizationId,
    role: OrganizationRole,
    teams: OrganizationTeamService,
): List<TeamOut> = teams.teamsForSubject(user.id)
    .filter { it.team.organizationId == orgId }
    .map { teamOut(user, it.team, role, teams) }

private suspend fun teamOut(
    user: User,
    team: OrganizationTeam,
    role: OrganizationRole,
    teams: OrganizationTeamService,
): TeamOut {
    val members = teams.members(user.id, team.id)
    val viewerRole = members.firstOrNull { it.userId == user.id }?.role
    val canManage = role != OrganizationRole.MEMBER || viewerRole == OrganizationTeamRole.MAINTAINER
    return TeamOut(
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
        members = members.map { it.toOut(user.id) },
        grants = teams.grants(user.id, team.id).map { it.toOut() },
    )
}

private suspend fun manageableCalendars(
    userId: UserId,
    orgId: OrganizationId,
    store: CalendarStore,
): List<CalendarOptionOut> = store.listCalendars(userId)
    .filter { it.organizationId == orgId && it.permission == CalendarPermission.OWNER }
    .sortedBy { it.displayName.lowercase() }
    .map { CalendarOptionOut(id = it.id.value, displayName = it.displayName, color = it.color) }

private fun OrganizationTeamMember.toOut(viewerId: UserId): TeamMemberOut = TeamMemberOut(
    userId = userId.value,
    username = username,
    displayName = displayName,
    avatarUrl = avatarVersion?.let { "/api/v1/users/${userId.value}/avatar?v=$it" },
    role = role.wire,
    isSelf = viewerId == userId,
)

private fun OrganizationTeamCalendarGrant.toOut(): TeamCalendarOut = TeamCalendarOut(
    calendarId = calendarId.value,
    displayName = calendarDisplayName,
    color = calendarColor,
    permission = permission.wire,
)
