package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.admin.AdminCalendarService
import dev.kolektiv.kalendee.admin.AdminUserService
import dev.kolektiv.kalendee.admin.AdminUserUpdate
import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.EmailVerificationPolicy
import dev.kolektiv.kalendee.auth.PublicAccessMode
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.groups.GroupService
import dev.kolektiv.keel.KeelAction

class AdminActions(
    private val auth: AuthService,
    private val settings: AuthSettings,
    private val groups: GroupService,
    private val adminUsers: AdminUserService,
    private val adminCalendars: AdminCalendarService,
) {
    @KeelAction("kalendee.setRegistration")
    suspend fun setRegistration(input: SetRegistrationIn): RegistrationOut = mapDomainErrors("open") {
        requireAdmin(auth, settings)
        auth.setRegistrationOpen(input.open)
        RegistrationOut(registrationOpen = auth.isRegistrationOpen())
    }

    @KeelAction("kalendee.setOauthRegistration")
    suspend fun setOauthRegistration(input: SetOauthRegistrationIn): SetOauthRegistrationOut =
        mapDomainErrors("open") {
            requireAdmin(auth, settings)
            auth.setOAuthRegistrationOpen(input.open)
            SetOauthRegistrationOut(oauthRegistrationOpen = auth.isOAuthRegistrationOpen())
        }

    @KeelAction("kalendee.setEmailVerification")
    suspend fun setEmailVerification(input: SetEmailVerificationIn): EmailVerificationPolicyOut =
        mapDomainErrors("policy") {
            requireAdmin(auth, settings)
            val policy = runCatching { EmailVerificationPolicy.parse(input.policy) }
                .getOrElse { throw CalendarException.Invalid("policy must be optional, soft, or required") }
            auth.setEmailVerificationPolicy(policy)
            EmailVerificationPolicyOut(policy = policy.wire)
        }

    @KeelAction("kalendee.setPublicAccess")
    suspend fun setPublicAccess(input: SetPublicAccessIn): PublicAccessOut = mapDomainErrors("mode") {
        requireAdmin(auth, settings)
        val mode = PublicAccessMode.parse(input.mode)
        if (mode == PublicAccessMode.INHERIT) {
            throw CalendarException.Invalid("public access must be public or signed_in")
        }
        auth.setPublicAccess(mode)
        PublicAccessOut(mode = auth.publicAccess())
    }

    @KeelAction("kalendee.adminUpdateUser")
    suspend fun adminUpdateUser(input: AdminUpdateUserIn): AdminUserSummary = mapDomainErrors("displayName") {
        val actor = requireAdmin(auth, settings)
        val updated = adminUsers.updateUserAsAdmin(
            actor.id,
            UserId.parse(input.userId),
            AdminUserUpdate(
                displayName = input.displayName,
                email = input.email,
                password = input.password,
                isAdmin = input.admin,
            ),
        )
        adminSummary(updated)
    }

    @KeelAction("kalendee.adminDeleteUser")
    suspend fun adminDeleteUser(input: AdminDeleteUserIn): DeletedOut = mapDomainErrors("userId") {
        val actor = requireAdmin(auth, settings)
        adminUsers.deleteUserAsAdmin(actor.id, UserId.parse(input.userId))
        DeletedOut()
    }

    @KeelAction("kalendee.adminDeleteCalendar")
    suspend fun adminDeleteCalendar(input: AdminDeleteCalendarIn): DeletedOut = mapDomainErrors("calendarId") {
        val actor = requireAdmin(auth, settings)
        val deleted = adminCalendars.adminDeleteCalendar(actor.id, CalendarId.parse(input.calendarId))
        if (!deleted) throw CalendarException.NotFound("calendar not found")
        DeletedOut()
    }

    @KeelAction("kalendee.adminSetCalendarPublic")
    suspend fun adminSetCalendarPublic(input: AdminSetCalendarPublicIn): AdminCalendarSummary =
        mapDomainErrors("calendarId") {
            val actor = requireAdmin(auth, settings)
            adminCalendars
                .adminSetCalendarPublicLink(actor.id, CalendarId.parse(input.calendarId), input.enabled)
                .toSummary()
        }

    @KeelAction("kalendee.adminGroups")
    suspend fun adminGroups(input: AdminGroupsIn): AdminGroupsOut = mapDomainErrors("name") {
        requireAdmin(auth, settings)
        AdminGroupsOut(groups = groups.listGroups().map { it.toSummary() })
    }

    @KeelAction("kalendee.adminCreateGroup")
    suspend fun adminCreateGroup(input: AdminCreateGroupIn): GroupSummary = mapDomainErrors("name") {
        requireAdmin(auth, settings)
        groups.createGroup(input.name, input.storageQuotaBytes).toSummary()
    }

    @KeelAction("kalendee.adminUpdateGroup")
    suspend fun adminUpdateGroup(input: AdminUpdateGroupIn): GroupSummary = mapDomainErrors("name") {
        requireAdmin(auth, settings)
        groups.updateGroup(
            id = input.groupId,
            name = input.name,
            quotaBytes = input.storageQuotaBytes,
            clearQuota = input.clearQuota,
        ).toSummary()
    }

    @KeelAction("kalendee.adminDeleteGroup")
    suspend fun adminDeleteGroup(input: AdminDeleteGroupIn): DeletedOut = mapDomainErrors("groupId") {
        requireAdmin(auth, settings)
        groups.deleteGroup(input.groupId)
        DeletedOut()
    }

    @KeelAction("kalendee.adminGroupMembers")
    suspend fun adminGroupMembers(input: AdminGroupMembersIn): AdminGroupMembersOut = mapDomainErrors("groupId") {
        requireAdmin(auth, settings)
        AdminGroupMembersOut(
            groupId = input.groupId,
            members = groups.members(input.groupId).map { it.toAdminGroupMember() },
        )
    }

    @KeelAction("kalendee.adminSetGroupMembers")
    suspend fun adminSetGroupMembers(input: AdminSetGroupMembersIn): AdminGroupMembersOut =
        mapDomainErrors("userIds") {
            val actor = requireAdmin(auth, settings)
            val members = groups.setMembers(
                input.groupId,
                input.userIds.map(UserId::parse),
                actor.id,
            )
            AdminGroupMembersOut(
                groupId = input.groupId,
                members = members.map { it.toAdminGroupMember() },
            )
        }

    private suspend fun adminSummary(user: User): AdminUserSummary = user.toAdminSummary(
        superadmin = groups.isSuperadmin(user.id),
        storageBytes = groups.usage(user.id),
        quotaBytes = groups.effectiveQuota(user.id),
        groups = groups.groupsFor(user.id),
    )
}
