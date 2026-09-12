package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.admin.AdminCalendar
import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CalendarShare
import dev.kolektiv.kalendee.calendar.CustomHoliday
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.HOLIDAY_CALENDAR_ID
import dev.kolektiv.kalendee.calendar.HolidayDefinition
import dev.kolektiv.kalendee.calendar.HolidayOccurrence
import dev.kolektiv.kalendee.calendar.HolidayPrefs
import dev.kolektiv.kalendee.calendar.Recurrence
import dev.kolektiv.kalendee.calendar.WellKnownHolidays
import dev.kolektiv.kalendee.groups.Group
import dev.kolektiv.kalendee.notifications.Notification
import dev.kolektiv.kalendee.organizations.Organization
import dev.kolektiv.kalendee.organizations.OrganizationInvitation
import dev.kolektiv.kalendee.organizations.OrganizationMember
import dev.kolektiv.keel.KeelType
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable

@KeelType
@Serializable
data class Viewer(
    val id: String,
    val username: String,
    val displayName: String,
    val timeZone: String,
    val accent: String = "primary",
    val admin: Boolean = false,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val avatarUrl: String? = null,
    val publicAccess: String = "inherit",
)

@KeelType
@Serializable
data class CalendarSummary(
    val id: String,
    val displayName: String,
    val description: String?,
    val timeZone: String,
    val color: String,
    val hidden: Boolean,
    val ownerName: String = "",
    val ownerAvatarUrl: String? = null,
    val permission: String = "owner",
    val publicLinkEnabled: Boolean = false,
    val publicLinkToken: String? = null,
    val requestsEnabled: Boolean = false,
    val accessMode: String = "inherit",
    val effectiveAccessMode: String = "public",
    val organizationId: String? = null,
    val organizationName: String? = null,
    val organizationSlug: String? = null,
)

@KeelType
@Serializable
data class RecurrenceSummary(
    val frequency: String,
    val interval: Int,
    val until: String?,
    val count: Int?,
)

@KeelType
@Serializable
data class EventSummary(
    val id: String,
    val calendarId: String,
    val title: String,
    val description: String?,
    val location: String?,
    val url: String?,
    val start: String,
    val end: String,
    val allDay: Boolean,
    val timeZone: String?,
    val status: String,
    val recurrence: RecurrenceSummary?,
    val etag: String,
    val openRsvp: Boolean = false,
    val rsvpStatus: String? = null,
    val attendeeCount: Int = 0,
)

@KeelType
@Serializable
data class EventAttendeeSummary(
    val id: String,
    val userId: String? = null,
    val username: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val email: String? = null,
    val name: String? = null,
    val status: String,
)

@KeelType
@Serializable
data class EventAttendeesOut(
    val attendees: List<EventAttendeeSummary>,
    val openRsvp: Boolean = false,
)

@KeelType
@Serializable
data class EventAttendeesIn(
    val eventId: String,
)

@KeelType
@Serializable
data class InviteToEventIn(
    val eventId: String,
    val username: String,
    val name: String? = null,
)

@KeelType
@Serializable
data class RemoveEventAttendeeIn(
    val eventId: String,
    val attendeeId: String,
)

@KeelType
@Serializable
data class RespondEventInviteIn(
    val eventId: String,
    val status: String,
)

@KeelType
@Serializable
data class SetEventOpenRsvpIn(
    val eventId: String,
    val enabled: Boolean,
)

@KeelType
@Serializable
data class RsvpOut(
    val status: String,
)

@KeelType
@Serializable
data class PublicRsvpIn(
    val eventId: String,
    val name: String,
    val email: String? = null,
    val status: String,
)

@KeelType
@Serializable
data class RsvpByTokenIn(
    val token: String,
    val status: String,
)

@KeelType("kalendee.home")
@Serializable
data class HomePage(
    val viewer: Viewer?,
    val viewingUser: Viewer?,
    val readOnly: Boolean,
    val registrationOpen: Boolean,
    val timeZone: String,
    val view: String,
    val date: String,
    val previousDate: String,
    val nextDate: String,
    val weekStart: String,
    val gridStart: String,
    val gridEnd: String,
    val label: String,
    val today: String,
    val now: String,
    val calendars: List<CalendarSummary>,
    val events: List<EventSummary>,
    val showHolidays: Boolean,
    val holidayCatalog: List<HolidayCatalogItem>,
    val subscribedHolidayIds: List<String>,
    val customHolidays: List<CustomHolidaySummary>,
    val friends: List<FriendSummary> = emptyList(),
    val friendRequests: List<FriendRequestSummary> = emptyList(),
)

@KeelType("kalendee.publicCalendar")
@Serializable
data class PublicCalendarPage(
    val token: String,
    val calendar: CalendarSummary,
    val events: List<EventSummary>,
    val viewer: Viewer?,
    val following: Boolean,
    val view: String,
    val date: String,
    val previousDate: String,
    val nextDate: String,
    val weekStart: String,
    val gridStart: String,
    val gridEnd: String,
    val label: String,
    val today: String,
    val now: String,
    val timeZone: String,
    val rssPath: String,
)

@KeelType("kalendee.login")
@Serializable
data class LoginPage(
    val viewer: Viewer?,
)

@KeelType("kalendee.register")
@Serializable
data class RegisterPage(
    val viewer: Viewer?,
    val registrationOpen: Boolean,
    val emailVerificationPolicy: String,
)

@KeelType("kalendee.admin")
@Serializable
data class AdminPage(
    val viewer: Viewer,
    val registrationOpen: Boolean,
    val emailVerificationPolicy: String,
    val publicAccess: String,
    val users: List<AdminUserSummary>,
    val showHolidays: Boolean,
    val holidayCatalog: List<HolidayCatalogItem>,
    val subscribedHolidayIds: List<String>,
    val customHolidays: List<CustomHolidaySummary>,
    val groups: List<GroupSummary> = emptyList(),
    val calendars: List<AdminCalendarSummary> = emptyList(),
)

@KeelType("kalendee.settings")
@Serializable
data class SettingsPage(
    val viewer: Viewer,
    val tab: String,
    val showHolidays: Boolean,
    val holidayCatalog: List<HolidayCatalogItem>,
    val subscribedHolidayIds: List<String>,
    val customHolidays: List<CustomHolidaySummary>,
)

@KeelType
@Serializable
data class AdminUserSummary(
    val id: String,
    val username: String,
    val displayName: String,
    val timeZone: String,
    val admin: Boolean,
    val calendars: List<CalendarSummary>,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val superadmin: Boolean = false,
    val storageBytes: Long = 0,
    val quotaBytes: Long? = null,
    val groups: List<String> = emptyList(),
)

@KeelType
@Serializable
data class GroupSummary(
    val id: String,
    val name: String,
    val isSystem: Boolean,
    val storageQuotaBytes: Long? = null,
    val memberCount: Long,
)

@KeelType
@Serializable
data class AdminCalendarSummary(
    val id: String,
    val displayName: String,
    val ownerUsername: String,
    val eventCount: Long,
    val publicLinkEnabled: Boolean,
)

@KeelType
@Serializable
data class AdminUpdateUserIn(
    val userId: String,
    val displayName: String? = null,
    val email: String? = null,
    val password: String? = null,
    val admin: Boolean? = null,
)

@KeelType
@Serializable
data class AdminDeleteUserIn(
    val userId: String,
)

@KeelType
@Serializable
data class AdminDeleteCalendarIn(
    val calendarId: String,
)

@KeelType
@Serializable
data class AdminSetCalendarPublicIn(
    val calendarId: String,
    val enabled: Boolean,
)

@KeelType
@Serializable
data object AdminGroupsIn

@KeelType
@Serializable
data class AdminGroupsOut(
    val groups: List<GroupSummary>,
)

@KeelType
@Serializable
data class AdminCreateGroupIn(
    val name: String,
    val storageQuotaBytes: Long? = null,
)

@KeelType
@Serializable
data class AdminUpdateGroupIn(
    val groupId: String,
    val name: String? = null,
    val storageQuotaBytes: Long? = null,
    val clearQuota: Boolean = false,
)

@KeelType
@Serializable
data class AdminDeleteGroupIn(
    val groupId: String,
)

@KeelType
@Serializable
data class AdminGroupMembersIn(
    val groupId: String,
)

@KeelType
@Serializable
data class AdminGroupMemberSummary(
    val userId: String,
    val username: String,
    val displayName: String,
    val admin: Boolean,
)

@KeelType
@Serializable
data class AdminGroupMembersOut(
    val groupId: String,
    val members: List<AdminGroupMemberSummary>,
)

@KeelType
@Serializable
data class AdminSetGroupMembersIn(
    val groupId: String,
    val userIds: List<String>,
)

@KeelType("kalendee.notFound")
@Serializable
data class NotFoundPage(
    val path: String,
    val title: String = "Not found",
)

@KeelType("kalendee.notifications")
@Serializable
data class NotificationsPage(
    val viewer: Viewer,
    val notifications: List<NotificationSummary>,
    val unreadCount: Int,
)

@KeelType("kalendee.verifyEmail")
@Serializable
data class VerifyEmailPage(
    val viewer: Viewer?,
    val token: String? = null,
)

@KeelType("kalendee.rsvp")
@Serializable
data class RsvpPage(
    val eventId: String,
    val token: String? = null,
    val valid: Boolean = false,
    val title: String? = null,
    val whenText: String? = null,
    val calendarName: String? = null,
    val status: String? = null,
    val requiresName: Boolean = false,
    val viewer: Viewer? = null,
)

@KeelType
@Serializable
data class LoginIn(
    val username: String,
    val password: String,
)

@KeelType
@Serializable
data class LoginOut(
    val viewer: Viewer? = null,
    val verificationRequired: Boolean = false,
    val email: String? = null,
)

@KeelType
@Serializable
data class RegisterIn(
    val username: String,
    val password: String,
    val email: String? = null,
)

@KeelType
@Serializable
data class RegisterOut(
    val viewer: Viewer? = null,
    val verificationRequired: Boolean = false,
    val email: String? = null,
)

@KeelType
@Serializable
data class VerifyEmailIn(
    val token: String,
)

@KeelType
@Serializable
data class VerifyEmailOut(
    val ok: Boolean,
    val email: String? = null,
)

@KeelType
@Serializable
data class ResendVerificationIn(
    val username: String,
)

@KeelType
@Serializable
data class ResendVerificationOut(
    val ok: Boolean = true,
)

@KeelType
@Serializable
data class NotificationSummary(
    val id: String,
    val kind: String,
    val title: String,
    val body: String? = null,
    val href: String? = null,
    val read: Boolean,
    val createdAt: String,
)

@KeelType
@Serializable
data class NotificationStateOut(
    val unreadCount: Int,
)

@KeelType
@Serializable
data class MarkNotificationReadIn(
    val id: String,
)

@KeelType
@Serializable
data object MarkAllNotificationsReadIn

@KeelType
@Serializable
data object LogoutIn

@KeelType
@Serializable
data class LogoutOut(
    val ok: Boolean = true,
)

@KeelType
@Serializable
data class CreateCalendarIn(
    val displayName: String,
    val description: String? = null,
    val timeZone: String? = null,
    val color: String? = null,
    val organizationId: String? = null,
)

@KeelType
@Serializable
data class UpdateCalendarIn(
    val id: String,
    val displayName: String,
    val description: String? = null,
    val timeZone: String = "UTC",
    val color: String,
)

@KeelType
@Serializable
data class DeleteCalendarIn(
    val id: String,
)

@KeelType
@Serializable
data class RecurrenceIn(
    val frequency: String,
    val interval: Int = 1,
    val until: String? = null,
    val count: Int? = null,
)

@KeelType
@Serializable
data class CreateEventIn(
    val calendarId: String,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val url: String? = null,
    val start: String,
    val end: String,
    val allDay: Boolean = false,
    val timeZone: String? = null,
    val recurrence: RecurrenceIn? = null,
)

@KeelType
@Serializable
data class UpdateEventIn(
    val id: String,
    val title: String? = null,
    val description: String? = null,
    val location: String? = null,
    val url: String? = null,
    val start: String? = null,
    val end: String? = null,
    val allDay: Boolean? = null,
    val recurrence: RecurrenceIn? = null,
    val clearRecurrence: Boolean = false,
    val etag: String? = null,
)

@KeelType
@Serializable
data class MoveEventIn(
    val id: String,
    val calendarId: String,
    val scope: String = "following",
    val from: String? = null,
    val etag: String? = null,
)

@KeelType
@Serializable
data class MoveEventOut(val events: List<EventSummary>)

@KeelType
@Serializable
data class DeleteEventIn(
    val id: String,
    val etag: String? = null,
)

@KeelType
@Serializable
data class DeletedOut(
    val ok: Boolean = true,
)

@KeelType
@Serializable
data class UpdateSettingsIn(
    val displayName: String,
    val timeZone: String,
    val accent: String = "primary",
    val email: String? = null,
    val clearEmail: Boolean = false,
)

@KeelType
@Serializable
data class SetCalendarHiddenIn(
    val id: String,
    val hidden: Boolean,
)

@KeelType
@Serializable
data class ShareCalendarIn(
    val calendarId: String,
    val username: String,
    val permission: String = "read",
)

@KeelType
@Serializable
data class UpdateShareIn(
    val calendarId: String,
    val userId: String,
    val permission: String,
)

@KeelType
@Serializable
data class RemoveShareIn(
    val calendarId: String,
    val userId: String,
)

@KeelType
@Serializable
data class GetCalendarSharingIn(
    val calendarId: String,
)

@KeelType
@Serializable
data class FriendSummary(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
)

@KeelType
@Serializable
data class FriendRequestSummary(
    val id: String,
    val user: FriendSummary,
    val createdAt: String,
)

@KeelType
@Serializable
data class SendFriendRequestIn(
    val username: String,
)

@KeelType
@Serializable
data class FriendRequestOut(
    val status: String,
    val friend: FriendSummary? = null,
)

@KeelType
@Serializable
data class RespondFriendRequestIn(
    val id: String,
)

@KeelType
@Serializable
data class RemoveFriendIn(
    val userId: String,
)

@KeelType
@Serializable
data object FriendsIn

@KeelType
@Serializable
data class FriendsOut(
    val friends: List<FriendSummary>,
    val incoming: List<FriendRequestSummary>,
)

@KeelType
@Serializable
data class SearchUsersIn(
    val query: String,
)

@KeelType
@Serializable
data class UserSearchResult(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val relationship: String,
)

@KeelType
@Serializable
data class UserSearchOut(
    val results: List<UserSearchResult>,
)

@KeelType
@Serializable
data class CalendarSharingOut(
    val shares: List<ShareSummary>,
    val publicLinkEnabled: Boolean,
    val publicLinkToken: String?,
    val followerCount: Int,
    val friends: List<FriendSummary> = emptyList(),
)

@KeelType
@Serializable
data class ShareSummary(
    val userId: String,
    val username: String,
    val displayName: String,
    val permission: String,
)

@KeelType
@Serializable
data class SetCalendarPublicIn(
    val calendarId: String,
    val enabled: Boolean,
)

@KeelType
@Serializable
data class RotatePublicLinkIn(
    val calendarId: String,
)

@KeelType
@Serializable
data class FollowCalendarIn(
    val token: String,
)

@KeelType
@Serializable
data class UnfollowCalendarIn(
    val calendarId: String,
)

@KeelType
@Serializable
data class FollowOut(
    val calendarId: String,
    val following: Boolean,
)

@KeelType
@Serializable
data class HolidayCatalogItem(
    val id: String,
    val name: String,
    val region: String,
)

@KeelType
@Serializable
data class CustomHolidaySummary(
    val id: String,
    val title: String,
    val month: Int,
    val day: Int,
)

@KeelType
@Serializable
data class HolidayStateOut(
    val showHolidays: Boolean,
    val subscribedIds: List<String>,
    val customHolidays: List<CustomHolidaySummary>,
)

@KeelType
@Serializable
data class SetShowHolidaysIn(
    val showHolidays: Boolean,
)

@KeelType
@Serializable
data class UpdateHolidaySubscriptionsIn(
    val subscribedIds: List<String>,
)

@KeelType
@Serializable
data class CreateCustomHolidayIn(
    val title: String,
    val month: Int,
    val day: Int,
)

@KeelType
@Serializable
data class DeleteCustomHolidayIn(
    val id: String,
)

@KeelType
@Serializable
data class SetRegistrationIn(
    val open: Boolean,
)

@KeelType
@Serializable
data class RegistrationOut(
    val registrationOpen: Boolean,
)

@KeelType
@Serializable
data class SetEmailVerificationIn(
    val policy: String,
)

@KeelType
@Serializable
data class EmailVerificationPolicyOut(
    val policy: String,
)

@KeelType
@Serializable
data class SetPublicAccessIn(
    val mode: String,
)

@KeelType
@Serializable
data class PublicAccessOut(
    val mode: String,
)

@KeelType
@Serializable
data object ReminderSettingsIn

@KeelType
@Serializable
data class UpdateReminderSettingsIn(
    val defaultOffsetsSeconds: List<Int>,
    val notifyAtStart: Boolean,
)

@KeelType
@Serializable
data class ReminderSettingsOut(
    val defaultOffsetsSeconds: List<Int>,
    val notifyAtStart: Boolean,
)

@KeelType
@Serializable
data class EventRemindersOut(
    val eventId: String,
    val offsetsSeconds: List<Int>,
    val useDefaults: Boolean,
    val defaultOffsetsSeconds: List<Int>,
    val notifyAtStart: Boolean,
)

@KeelType
@Serializable
data class SetEventRemindersIn(
    val eventId: String,
    val offsetsSeconds: List<Int>,
    val useDefaults: Boolean,
)

@KeelType
@Serializable
data class GetEventRemindersIn(
    val eventId: String,
)

@KeelType
@Serializable
data class UpcomingRemindersIn(
    val hours: Int = 48,
)

@KeelType
@Serializable
data class ReminderInstanceOut(
    val eventId: String,
    val calendarId: String,
    val calendarName: String,
    val calendarColor: String,
    val title: String,
    val start: String,
    val allDay: Boolean,
    val offsetSeconds: Int,
    val remindAt: String,
)

@KeelType
@Serializable
data class AvailabilityWindowIn(
    val weekday: Int,
    val startMinute: Int,
    val endMinute: Int,
)

@KeelType
@Serializable
data class CalendarAvailabilityIn(
    val calendarId: String,
)

@KeelType
@Serializable
data class CalendarAvailabilityOut(
    val calendarId: String,
    val requestsEnabled: Boolean,
    val slotMinutes: Int,
    val accessMode: String,
    val effectiveAccessMode: String,
    val timeZone: String,
    val windows: List<AvailabilityWindowIn>,
)

@KeelType
@Serializable
data class UpdateCalendarAvailabilityIn(
    val calendarId: String,
    val requestsEnabled: Boolean,
    val slotMinutes: Int = 60,
    val accessMode: String = "inherit",
    val windows: List<AvailabilityWindowIn> = emptyList(),
)

@KeelType
@Serializable
data class AvailabilitySlotOut(
    val start: String,
    val end: String,
    val available: Boolean,
)

@KeelType
@Serializable
data class AvailabilityDayOut(
    val date: String,
    val slots: List<AvailabilitySlotOut>,
)

@KeelType
@Serializable
data class CalendarSlotsIn(
    val calendarId: String,
    val from: String,
    val to: String,
)

@KeelType
@Serializable
data class CalendarSlotsOut(
    val calendarId: String,
    val timeZone: String,
    val requestsEnabled: Boolean,
    val days: List<AvailabilityDayOut>,
)

@KeelType
@Serializable
data class RequestTimeSlotIn(
    val calendarId: String,
    val start: String,
    val end: String,
    val message: String? = null,
)

@KeelType
@Serializable
data class RequestTimeSlotOut(
    val id: String,
    val status: String,
)

@KeelType
@Serializable
data class TimeSlotRequestSummary(
    val id: String,
    val calendarId: String,
    val calendarName: String,
    val requesterUserId: String? = null,
    val requesterName: String? = null,
    val requesterUsername: String? = null,
    val requesterAvatarUrl: String? = null,
    val requesterEmail: String? = null,
    val start: String,
    val end: String,
    val message: String? = null,
    val status: String,
    val createdAt: String,
)

@KeelType
@Serializable
data class PublicRequestTimeSlotIn(
    val calendarToken: String,
    val name: String,
    val email: String? = null,
    val start: String,
    val end: String,
    val message: String? = null,
)

@KeelType
@Serializable
data class CalendarRequestsIn(
    val calendarId: String,
)

@KeelType
@Serializable
data class CalendarRequestsOut(
    val requests: List<TimeSlotRequestSummary>,
)

@KeelType
@Serializable
data class RespondTimeSlotIn(
    val id: String,
    val accept: Boolean,
    val message: String? = null,
)

@KeelType
@Serializable
data class SetUserPublicAccessIn(
    val mode: String,
)

@KeelType
@Serializable
data class OrganizationSummary(
    val id: String,
    val slug: String,
    val displayName: String,
    val description: String? = null,
    val visibility: String = "private",
    val avatarUrl: String? = null,
    val role: String? = null,
    val memberCount: Int = 0,
)

@KeelType
@Serializable
data class OrganizationMemberSummary(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val role: String,
    val isSelf: Boolean = false,
)

@KeelType
@Serializable
data class OrganizationInvitationSummary(
    val id: String,
    val email: String? = null,
    val userId: String? = null,
    val username: String? = null,
    val displayName: String? = null,
    val role: String,
    val status: String,
    val createdAt: String,
    val expiresAt: String,
)

@KeelType("kalendee.profile")
@Serializable
data class PublicProfilePage(
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val isSelf: Boolean = false,
    val calendars: List<CalendarSummary> = emptyList(),
    val organizations: List<OrganizationSummary> = emptyList(),
    val viewer: Viewer? = null,
)

@KeelType("kalendee.org")
@Serializable
data class OrganizationProfilePage(
    val org: OrganizationSummary,
    val viewer: Viewer? = null,
    val viewerRole: String? = null,
    val pendingInvitation: OrganizationInvitationSummary? = null,
    val inviteToken: String? = null,
    val members: List<OrganizationMemberSummary> = emptyList(),
    val calendars: List<CalendarSummary> = emptyList(),
    val canManageSettings: Boolean = false,
)

@KeelType("kalendee.orgSettings")
@Serializable
data class OrganizationSettingsPage(
    val org: OrganizationSummary,
    val viewer: Viewer,
    val viewerRole: String,
    val members: List<OrganizationMemberSummary> = emptyList(),
    val invitations: List<OrganizationInvitationSummary> = emptyList(),
    val canManageMembers: Boolean = false,
    val canManageOwners: Boolean = false,
)

@KeelType
@Serializable
data class DirectoryOrgSummary(
    val id: String,
    val slug: String,
    val displayName: String,
    val description: String? = null,
    val avatarUrl: String? = null,
    val memberCount: Int = 0,
    val viewerRole: String? = null,
)

@KeelType
@Serializable
data class DirectoryUserSummary(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
)

@KeelType
@Serializable
data class DirectoryCalendarSummary(
    val id: String,
    val displayName: String,
    val token: String,
    val ownerName: String,
    val ownerUsername: String? = null,
    val organizationName: String? = null,
    val color: String = "primary",
)

@KeelType("kalendee.directory")
@Serializable
data class PublicDirectoryPage(
    val viewer: Viewer? = null,
    val publicAccess: String = "public",
    val orgs: List<DirectoryOrgSummary> = emptyList(),
    val users: List<DirectoryUserSummary> = emptyList(),
    val calendars: List<DirectoryCalendarSummary> = emptyList(),
)

@KeelType
@Serializable
data object OrganizationsIn

@KeelType
@Serializable
data class OrganizationsOut(
    val organizations: List<OrganizationSummary>,
)

@KeelType
@Serializable
data class CreateOrganizationIn(
    val slug: String,
    val displayName: String,
    val description: String? = null,
)

@KeelType
@Serializable
data class UpdateOrganizationIn(
    val organizationId: String,
    val displayName: String? = null,
    val description: String? = null,
    val visibility: String? = null,
)

@KeelType
@Serializable
data class DeleteOrganizationIn(
    val organizationId: String,
)

@KeelType
@Serializable
data class OrganizationMembersIn(
    val organizationId: String,
)

@KeelType
@Serializable
data class OrganizationMembersOut(
    val organizationId: String,
    val viewerRole: String? = null,
    val canManageMembers: Boolean = false,
    val canManageOwners: Boolean = false,
    val members: List<OrganizationMemberSummary> = emptyList(),
)

@KeelType
@Serializable
data class OrganizationInvitationsIn(
    val organizationId: String,
)

@KeelType
@Serializable
data class OrganizationInvitationsOut(
    val organizationId: String,
    val invitations: List<OrganizationInvitationSummary> = emptyList(),
)

@KeelType
@Serializable
data class InviteToOrganizationIn(
    val organizationId: String,
    val identifier: String,
    val role: String = "member",
)

@KeelType
@Serializable
data class OrganizationInvitationIn(
    val invitationId: String,
    val organizationId: String? = null,
)

@KeelType
@Serializable
data class OrganizationInvitationOut(
    val id: String,
    val status: String,
)

@KeelType
@Serializable
data class SetOrganizationMemberRoleIn(
    val organizationId: String,
    val userId: String,
    val role: String,
)

@KeelType
@Serializable
data class RemoveOrganizationMemberIn(
    val organizationId: String,
    val userId: String,
)

@KeelType
@Serializable
data class RespondOrganizationInvitationIn(
    val invitationId: String? = null,
    val token: String? = null,
)

@KeelType
@Serializable
data class OrganizationMembershipOut(
    val organization: OrganizationSummary,
    val role: String,
)

fun Organization.toSummary(
    role: String? = null,
    memberCount: Int = 0,
): OrganizationSummary = OrganizationSummary(
    id = id.value,
    slug = slug,
    displayName = displayName,
    description = description,
    visibility = visibility.wire,
    avatarUrl = null,
    role = role,
    memberCount = memberCount,
)

fun OrganizationMember.toSummary(viewerId: UserId? = null): OrganizationMemberSummary =
    OrganizationMemberSummary(
        userId = userId.value,
        username = username,
        displayName = displayName,
        avatarUrl = avatarVersion?.let { avatarUrl(userId, it) },
        role = role.wire,
        isSelf = viewerId != null && viewerId == userId,
    )

fun OrganizationInvitation.toSummary(user: User? = null): OrganizationInvitationSummary =
    OrganizationInvitationSummary(
        id = id.toString(),
        email = email,
        userId = inviteeUserId?.value,
        username = user?.username,
        displayName = user?.displayName,
        role = role.wire,
        status = status,
        createdAt = createdAt.toString(),
        expiresAt = expiresAt.toString(),
    )

fun avatarUrl(id: UserId, version: Long): String = "/api/v1/users/${id.value}/avatar?v=$version"

fun User.toViewer(): Viewer = Viewer(
    id = id.value,
    username = username,
    displayName = displayName,
    timeZone = timeZone,
    accent = accent,
    admin = admin,
    email = email,
    emailVerified = emailVerified,
    avatarUrl = avatarVersion?.let { avatarUrl(id, it) },
    publicAccess = publicAccess.wire,
)

fun Calendar.toSummary(
    ownerName: String = "",
    effectiveAccessMode: String = "public",
    organization: OrganizationSummary? = null,
): CalendarSummary = CalendarSummary(
    id = id.value,
    displayName = displayName,
    description = description,
    timeZone = timeZone,
    color = color,
    hidden = hidden,
    ownerName = organization?.displayName ?: ownerName,
    ownerAvatarUrl = if (organization != null) {
        organization.avatarUrl
    } else {
        ownerAvatarVersion
            ?.takeIf { permission != CalendarPermission.OWNER }
            ?.let { avatarUrl(ownerId, it) }
    },
    permission = permission.wire,
    publicLinkEnabled = publicLinkEnabled,
    publicLinkToken = publicLinkToken,
    requestsEnabled = requestsEnabled,
    accessMode = accessMode.wire,
    effectiveAccessMode = effectiveAccessMode,
    organizationId = organization?.id ?: organizationId?.value,
    organizationName = organization?.displayName,
    organizationSlug = organization?.slug,
)

suspend fun Calendar.toSummary(
    auth: AuthService,
    ownerName: String = "",
    organization: OrganizationSummary? = null,
): CalendarSummary = toSummary(
    ownerName = ownerName,
    effectiveAccessMode = auth.effectivePublicAccess(this).wire,
    organization = organization,
)

fun CalendarShare.toSummary(): ShareSummary = ShareSummary(
    userId = userId.value,
    username = username,
    displayName = displayName,
    permission = permission.wire,
)

fun Group.toSummary(): GroupSummary = GroupSummary(
    id = id,
    name = name,
    isSystem = isSystem,
    storageQuotaBytes = storageQuotaBytes,
    memberCount = memberCount,
)

fun AdminCalendar.toSummary(): AdminCalendarSummary = AdminCalendarSummary(
    id = id,
    displayName = displayName,
    ownerUsername = ownerUsername,
    eventCount = eventCount,
    publicLinkEnabled = publicLinkEnabled,
)

fun User.toAdminGroupMember(): AdminGroupMemberSummary = AdminGroupMemberSummary(
    userId = id.value,
    username = username,
    displayName = displayName,
    admin = admin,
)

fun User.toAdminSummary(
    superadmin: Boolean = false,
    storageBytes: Long = 0,
    quotaBytes: Long? = null,
    groups: List<String> = emptyList(),
    calendars: List<CalendarSummary> = emptyList(),
): AdminUserSummary = AdminUserSummary(
    id = id.value,
    username = username,
    displayName = displayName,
    timeZone = timeZone,
    admin = admin,
    calendars = calendars,
    email = email,
    emailVerified = emailVerified,
    superadmin = superadmin,
    storageBytes = storageBytes,
    quotaBytes = quotaBytes,
    groups = groups,
)

fun Event.toSummary(): EventSummary = EventSummary(
    id = id.value,
    calendarId = calendarId.value,
    title = title,
    description = description,
    location = location,
    url = url,
    start = start.toString(),
    end = end.toString(),
    allDay = allDay,
    timeZone = timeZone,
    status = status.name,
    recurrence = recurrence?.toSummary(),
    etag = etag,
    openRsvp = openRsvp,
    rsvpStatus = rsvpStatus,
    attendeeCount = 0,
)

fun Recurrence.toSummary(): RecurrenceSummary = RecurrenceSummary(
    frequency = frequency.name,
    interval = interval,
    until = until?.toString(),
    count = count,
)

fun HolidayDefinition.toCatalogItem(): HolidayCatalogItem = HolidayCatalogItem(
    id = id,
    name = name,
    region = region,
)

fun CustomHoliday.toSummary(): CustomHolidaySummary = CustomHolidaySummary(
    id = id,
    title = title,
    month = month,
    day = day,
)

fun Notification.toSummary(): NotificationSummary = NotificationSummary(
    id = id.toString(),
    kind = kind,
    title = title,
    body = body,
    href = href,
    read = readAt != null,
    createdAt = createdAt.toString(),
)

fun HolidayPrefs.toState(): HolidayStateOut = HolidayStateOut(
    showHolidays = showHolidays,
    subscribedIds = subscribedIds,
    customHolidays = custom.map { it.toSummary() },
)

fun HolidayOccurrence.toEventSummary(zone: TimeZone): EventSummary {
    val start = date.atStartOfDayIn(zone)
    val end = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
    return EventSummary(
        id = id,
        calendarId = HOLIDAY_CALENDAR_ID,
        title = title,
        description = null,
        location = null,
        url = null,
        start = start.toString(),
        end = end.toString(),
        allDay = true,
        timeZone = zone.id,
        status = "CONFIRMED",
        recurrence = null,
        etag = "holiday",
    )
}

fun holidayCatalog(): List<HolidayCatalogItem> = WellKnownHolidays.map { it.toCatalogItem() }

fun emptyHolidayState(): HolidayStateOut = HolidayStateOut(
    showHolidays = false,
    subscribedIds = emptyList(),
    customHolidays = emptyList(),
)
