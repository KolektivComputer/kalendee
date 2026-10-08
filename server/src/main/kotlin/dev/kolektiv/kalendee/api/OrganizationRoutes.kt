package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.OrganizationVisibility
import dev.kolektiv.kalendee.organizations.Organization
import dev.kolektiv.kalendee.organizations.OrganizationInvitation
import dev.kolektiv.kalendee.organizations.OrganizationMember
import dev.kolektiv.kalendee.organizations.OrganizationRole
import dev.kolektiv.kalendee.organizations.OrganizationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.organizationRoutes(
    organizations: OrganizationService,
    auth: AuthService,
) {
    route("/organizations") {
        get {
            val user = call.user()
            call.respond(
                OrganizationsResponse(
                    organizations = organizations.listFor(user.id).map { membership ->
                        summaryOut(organizations, membership.organization, user.id, membership.role)
                    },
                ),
            )
        }
        post {
            val user = call.user()
            val body = call.receive<CreateOrganizationBody>()
            val organization = organizations.create(user.id, body.slug, body.displayName, body.description)
            call.respond(
                HttpStatusCode.Created,
                summaryOut(organizations, organization, user.id, OrganizationRole.OWNER, memberCount = 1),
            )
        }
        post("/invitations/accept") {
            val user = call.user()
            val body = call.receive<AcceptOrganizationInvitationBody>()
            val membership = when {
                !body.invitationId.isNullOrBlank() -> organizations.acceptInvitation(user.id, body.invitationId)
                !body.token.isNullOrBlank() -> organizations.acceptInvitationByToken(user.id, body.token)
                else -> throw CalendarException.Invalid("invitation id or token is required")
            }
            call.respond(
                OrganizationMembershipResponse(
                    organization = summaryOut(
                        organizations,
                        membership.organization,
                        user.id,
                        membership.role,
                    ),
                    role = membership.role.wire,
                ),
            )
        }
        post("/invitations/decline") {
            val user = call.user()
            val body = call.receive<DeclineOrganizationInvitationBody>()
            val invitationId = body.invitationId.takeIf { it.isNotBlank() }
                ?: throw CalendarException.Invalid("invitation id is required")
            organizations.declineInvitation(user.id, invitationId)
            call.respond(HttpStatusCode.NoContent)
        }
        patch("/{id}") {
            val user = call.user()
            val orgId = call.organizationId()
            val body = call.receive<UpdateOrganizationBody>()
            val visibility = body.visibility
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(OrganizationVisibility::parse)
            val updated = organizations.update(user.id, orgId, body.displayName, body.description, visibility)
            call.respond(summaryOut(organizations, updated, user.id, organizations.role(orgId, user.id)))
        }
        delete("/{id}") {
            val user = call.user()
            organizations.delete(user.id, call.organizationId())
            call.respond(HttpStatusCode.NoContent)
        }
        get("/{id}/members") {
            val user = call.user()
            val orgId = call.organizationId()
            val role = organizations.role(orgId, user.id)
            call.respond(
                OrganizationMembersResponse(
                    organizationId = orgId.value,
                    viewerRole = role?.wire,
                    canManageMembers = role != null && role != OrganizationRole.MEMBER,
                    canManageOwners = role == OrganizationRole.OWNER,
                    members = organizations.members(user.id, orgId).map { it.toOut(user.id) },
                ),
            )
        }
        get("/{id}/invitations") {
            val user = call.user()
            val orgId = call.organizationId()
            call.respond(
                OrganizationInvitationsResponse(
                    organizationId = orgId.value,
                    invitations = organizations.invitations(user.id, orgId)
                        .map { it.toOut(auth) },
                ),
            )
        }
        post("/{id}/invitations") {
            val user = call.user()
            val orgId = call.organizationId()
            val body = call.receive<CreateOrganizationInvitationBody>()
            val result = organizations.invite(
                user.id,
                orgId,
                body.identifier,
                OrganizationRole.parse(body.role),
            )
            call.respond(
                HttpStatusCode.Created,
                OrganizationInvitationResultOut(id = result.invitationId.toString(), status = result.status),
            )
        }
        delete("/{id}/invitations/{invitationId}") {
            val user = call.user()
            val invitationId = call.parameters["invitationId"]
                ?: throw CalendarException.Invalid("missing invitation id")
            val revoked = organizations.revokeInvitation(user.id, invitationId)
            call.respond(
                OrganizationInvitationResultOut(id = revoked.id.toString(), status = revoked.status),
            )
        }
        patch("/{id}/members/{userId}") {
            val user = call.user()
            val body = call.receive<SetOrganizationMemberRoleBody>()
            val updated = organizations.updateMemberRole(
                user.id,
                call.organizationId(),
                call.userId(),
                OrganizationRole.parse(body.role),
            )
            call.respond(updated.toOut(user.id))
        }
        delete("/{id}/members/{userId}") {
            val user = call.user()
            organizations.removeMember(user.id, call.organizationId(), call.userId())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private suspend fun summaryOut(
    organizations: OrganizationService,
    organization: Organization,
    viewerId: UserId,
    role: OrganizationRole?,
    memberCount: Int? = null,
): OrganizationSummaryOut = OrganizationSummaryOut(
    id = organization.id.value,
    slug = organization.slug,
    displayName = organization.displayName,
    description = organization.description,
    visibility = organization.visibility.wire,
    avatarUrl = null,
    role = role?.wire,
    memberCount = memberCount ?: organizations.members(viewerId, organization.id).size,
)

private fun OrganizationMember.toOut(viewerId: UserId): OrganizationMemberOut = OrganizationMemberOut(
    userId = userId.value,
    username = username,
    displayName = displayName,
    avatarUrl = avatarVersion?.let { "/api/v1/users/${userId.value}/avatar?v=$it" },
    role = role.wire,
    isSelf = viewerId == userId,
)

private suspend fun OrganizationInvitation.toOut(auth: AuthService): OrganizationInvitationOut {
    val invitee = inviteeUserId?.let { auth.userById(it) } ?: email?.let { auth.userByIdentifier(it) }
    return OrganizationInvitationOut(
        id = id.toString(),
        email = email,
        userId = inviteeUserId?.value,
        username = invitee?.username,
        displayName = invitee?.displayName,
        role = role.wire,
        status = status,
        createdAt = createdAt.toString(),
        expiresAt = expiresAt.toString(),
    )
}
