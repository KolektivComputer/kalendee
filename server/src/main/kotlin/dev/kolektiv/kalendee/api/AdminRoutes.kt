package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.admin.AdminCalendar
import dev.kolektiv.kalendee.admin.AdminCalendarService
import dev.kolektiv.kalendee.admin.AdminUserService
import dev.kolektiv.kalendee.admin.AdminUserUpdate
import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.groups.Group
import dev.kolektiv.kalendee.groups.GroupService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.adminRoutes(
    auth: AuthService,
    groups: GroupService,
    adminUsers: AdminUserService,
    adminCalendars: AdminCalendarService,
) {
    route("/admin") {
        get("/users") {
            call.requireAdminUser()
            call.respond(auth.listUsers().map { it.toAdminOut(groups) })
        }
        patch("/users/{id}") {
            val actor = call.requireAdminUser()
            val body = call.receive<AdminUpdateUserBody>()
            val updated = adminUsers.updateUserAsAdmin(
                actor.id,
                call.adminUserId(),
                AdminUserUpdate(
                    displayName = body.displayName,
                    email = body.email,
                    password = body.password,
                    isAdmin = body.admin,
                ),
            )
            call.respond(updated.toAdminOut(groups))
        }
        delete("/users/{id}") {
            val actor = call.requireAdminUser()
            adminUsers.deleteUserAsAdmin(actor.id, call.adminUserId())
            call.respond(HttpStatusCode.NoContent)
        }
        get("/calendars") {
            call.requireAdminUser()
            call.respond(adminCalendars.listCalendars().map { it.toAdminOut() })
        }
        delete("/calendars/{id}") {
            val actor = call.requireAdminUser()
            val deleted = adminCalendars.adminDeleteCalendar(actor.id, call.calendarId())
            if (!deleted) throw CalendarException.NotFound("calendar not found")
            call.respond(HttpStatusCode.NoContent)
        }
        patch("/calendars/{id}") {
            val actor = call.requireAdminUser()
            val body = call.receive<AdminSetCalendarPublicBody>()
            call.respond(
                adminCalendars
                    .adminSetCalendarPublicLink(actor.id, call.calendarId(), body.enabled)
                    .toAdminOut(),
            )
        }
        get("/groups") {
            call.requireAdminUser()
            call.respond(groups.listGroups().map { it.toAdminOut() })
        }
        post("/groups") {
            call.requireAdminUser()
            val body = call.receive<AdminCreateGroupBody>()
            call.respond(
                HttpStatusCode.Created,
                groups.createGroup(body.name, body.storageQuotaBytes).toAdminOut(),
            )
        }
        patch("/groups/{id}") {
            call.requireAdminUser()
            val body = call.receive<AdminUpdateGroupBody>()
            call.respond(
                groups.updateGroup(
                    id = call.groupId(),
                    name = body.name,
                    quotaBytes = body.storageQuotaBytes,
                    clearQuota = body.clearQuota,
                ).toAdminOut(),
            )
        }
        delete("/groups/{id}") {
            call.requireAdminUser()
            groups.deleteGroup(call.groupId())
            call.respond(HttpStatusCode.NoContent)
        }
        get("/groups/{id}/members") {
            call.requireAdminUser()
            val groupId = call.groupId()
            call.respond(AdminGroupMembersResponse(groupId, groups.members(groupId).map { it.toAdminMember() }))
        }
        put("/groups/{id}/members") {
            val actor = call.requireAdminUser()
            val body = call.receive<AdminSetGroupMembersBody>()
            val groupId = call.groupId()
            val members = groups.setMembers(groupId, body.userIds.map(UserId::parse), actor.id)
            call.respond(AdminGroupMembersResponse(groupId, members.map { it.toAdminMember() }))
        }
    }
}

private fun ApplicationCall.requireAdminUser(): User {
    val user = user()
    if (!user.admin) throw CalendarException.Forbidden("admin only")
    return user
}

private fun ApplicationCall.adminUserId(): UserId =
    UserId.parse(parameters["id"] ?: throw CalendarException.Invalid("missing user id"))

private fun ApplicationCall.groupId(): String =
    parameters["id"]?.takeIf { it.isNotBlank() } ?: throw CalendarException.Invalid("missing group id")

private suspend fun User.toAdminOut(groups: GroupService): AdminUserOut = AdminUserOut(
    id = id.value,
    username = username,
    displayName = displayName,
    email = email,
    emailVerified = emailVerified,
    admin = admin,
    superadmin = groups.isSuperadmin(id),
    timeZone = timeZone,
    storageBytes = groups.usage(id),
    quotaBytes = groups.effectiveQuota(id),
    groups = groups.groupsFor(id),
    createdAt = createdAt.toString(),
)

private fun AdminCalendar.toAdminOut(): AdminCalendarOut = AdminCalendarOut(
    id = id,
    displayName = displayName,
    ownerUsername = ownerUsername,
    eventCount = eventCount,
    publicLinkEnabled = publicLinkEnabled,
)

private fun Group.toAdminOut(): AdminGroupOut = AdminGroupOut(
    id = id,
    name = name,
    isSystem = isSystem,
    storageQuotaBytes = storageQuotaBytes,
    memberCount = memberCount,
)

private fun User.toAdminMember(): AdminGroupMemberOut = AdminGroupMemberOut(
    userId = id.value,
    username = username,
    displayName = displayName,
    admin = admin,
)
