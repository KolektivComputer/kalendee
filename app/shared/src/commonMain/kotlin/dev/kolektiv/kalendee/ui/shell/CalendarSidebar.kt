package dev.kolektiv.kalendee.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.client.OrganizationSummaryOut
import dev.kolektiv.kalendee.ui.AppState
import dev.kolektiv.kalendee.ui.AppUiState
import dev.kolektiv.kalendee.ui.CalendarUi
import dev.kolektiv.kalendee.ui.ServerUi
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens
import dev.kolektiv.kalendee.ui.design.components.DAvatar
import dev.kolektiv.kalendee.ui.design.components.DBadge
import dev.kolektiv.kalendee.ui.design.components.DBadgeColor
import dev.kolektiv.kalendee.ui.design.components.DBadgeSize
import dev.kolektiv.kalendee.ui.design.components.DButton
import dev.kolektiv.kalendee.ui.design.components.DButtonVariant
import dev.kolektiv.kalendee.ui.design.components.DIcon
import dev.kolektiv.kalendee.ui.design.components.DIconButton
import dev.kolektiv.kalendee.ui.design.components.DListItem
import dev.kolektiv.kalendee.ui.design.components.DSpinner
import dev.kolektiv.kalendee.ui.design.medium
import dev.kolektiv.kalendee.ui.design.semibold
import dev.kolektiv.kalendee.ui.design.rememberCalendarColorSpec
import dev.kolektiv.kalendee.ui.icons.Lucide
import dev.kolektiv.kalendee.ui.nav.Route
import kotlinx.coroutines.launch

private const val SidebarVersion = "0.1.0"

/**
 * Sidebar / drawer content, mirroring the web sidebar order: calendars grouped
 * by personal and organization, then organizations without calendars, friends
 * (incoming requests first), the Upcoming and Settings destinations, and a
 * version footer.
 *
 * [onNavigate] is responsible for closing the drawer on phones.
 */
@Composable
fun CalendarSidebar(
    state: AppState,
    current: Route,
    onNavigate: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by state.state.collectAsState()
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    val servers = ui.servers.filter { it.account.profile.enabled }
    val unread = ui.notifications.count { !it.notification.read }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = dimens.space2, bottom = dimens.space4),
    ) {
        SidebarSectionHeader(
            icon = Lucide.Calendar,
            title = "Calendars",
            count = servers.sumOf { it.calendars.size },
        )
        if (servers.isEmpty()) {
            Column(modifier = Modifier.padding(horizontal = dimens.space3)) {
                DText(
                    text = "No servers connected yet.",
                    style = DType.sm,
                    color = colors.mutedContent,
                )
                Spacer(Modifier.height(dimens.space2))
                DButton(
                    onClick = { onNavigate(Route.AddServer) },
                    variant = DButtonVariant.Secondary,
                ) {
                    DText("Connect a server")
                }
            }
        } else {
            servers.forEach { server ->
                if (servers.size > 1) {
                    ServerSubheader(name = server.account.profile.name, connected = server.online != false)
                }
                CalendarGroups(server = server, ui = ui, state = state, onNavigate = onNavigate)
                ServerError(server)
            }
            if (ui.loading && servers.all { it.calendars.isEmpty() }) {
                LoadingRow(label = "Loading calendars…")
            }
        }

        val organizationRows = servers.flatMap { server ->
            groupCalendars(
                calendars = server.calendars,
                organizations = ui.organizationsByServer[server.account.profile.id].orEmpty(),
            ).ungroupedOrganizations.map { server to it }
        }
        if (organizationRows.isNotEmpty()) {
            SidebarSectionHeader(icon = Lucide.Building2, title = "Organizations")
            organizationRows.forEach { (server, organization) ->
                if (servers.size > 1) {
                    ServerSubheader(name = server.account.profile.name, connected = server.online != false)
                }
                OrganizationRow(organization)
            }
        }

        SidebarSectionHeader(
            icon = Lucide.Users,
            title = "Friends",
            trailing = {
                DIconButton(
                    icon = Lucide.RefreshCw,
                    contentDescription = "Refresh friends",
                    onClick = { state.scope.launch { state.refreshSocial() } },
                    size = 40.dp,
                    iconSize = 16.dp,
                    tint = colors.mutedContent,
                )
            },
        )
        FriendSections(servers = servers.filter { it.signedIn }, ui = ui)

        Spacer(Modifier.height(dimens.space2))
        DListItem(
            title = "Upcoming",
            selected = current == Route.Upcoming,
            onClick = { onNavigate(Route.Upcoming) },
            leading = { DIcon(icon = Lucide.Bell, size = 18.dp) },
            trailing = if (unread > 0) {
                {
                    DBadge(
                        text = if (unread > 9) "9+" else unread.toString(),
                        color = DBadgeColor.Error,
                        size = DBadgeSize.Xs,
                    )
                }
            } else {
                null
            },
        )
        DListItem(
            title = "Settings",
            selected = current == Route.Settings,
            onClick = { onNavigate(Route.Settings) },
            leading = { DIcon(icon = Lucide.Settings, size = 18.dp) },
        )

        DText(
            text = "Kalendee $SidebarVersion",
            style = DType.xs,
            color = colors.mutedContent,
            modifier = Modifier.padding(start = dimens.space3, top = dimens.space4),
        )
    }
}

@Composable
private fun CalendarGroups(
    server: ServerUi,
    ui: AppUiState,
    state: AppState,
    onNavigate: (Route) -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val scope = rememberCoroutineScope()
    val groups = groupCalendars(
        calendars = server.calendars,
        organizations = ui.organizationsByServer[server.account.profile.id].orEmpty(),
    )
    if (server.calendars.isEmpty()) {
        DText(
            text = if (server.online == false) "Unreachable" else "No calendars",
            style = DType.xs,
            color = if (server.online == false) colors.error else colors.mutedContent,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        return
    }
    if (groups.personal.isNotEmpty()) {
        GroupHeading("Personal")
        groups.personal.forEach { calendar ->
            CalendarRow(
                calendar = calendar,
                onClick = { onNavigate(Route.Calendar) },
                onToggle = { visible ->
                    scope.launch {
                        state.setCalendarVisible(server.account.profile.id, calendar.calendar.id.value, visible)
                    }
                },
            )
        }
    }
    groups.organizations.forEach { group ->
        GroupHeading(group.organization.displayName)
        group.calendars.forEach { calendar ->
            CalendarRow(
                calendar = calendar,
                onClick = { onNavigate(Route.Calendar) },
                onToggle = { visible ->
                    scope.launch {
                        state.setCalendarVisible(server.account.profile.id, calendar.calendar.id.value, visible)
                    }
                },
            )
        }
    }
}

@Composable
private fun CalendarRow(
    calendar: CalendarUi,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val spec = rememberCalendarColorSpec(calendar.calendar.color, calendar.calendar.id.value)
    DListItem(
        title = calendar.calendar.displayName,
        onClick = onClick,
        modifier = Modifier.alpha(if (calendar.visible) 1f else 0.55f),
        leading = {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(spec.fill),
            )
        },
        trailing = {
            DIconButton(
                icon = if (calendar.visible) Lucide.Eye else Lucide.EyeOff,
                contentDescription = if (calendar.visible) {
                    "Hide ${calendar.calendar.displayName}"
                } else {
                    "Show ${calendar.calendar.displayName}"
                },
                onClick = { onToggle(!calendar.visible) },
                size = 40.dp,
                iconSize = 18.dp,
                tint = if (calendar.visible) colors.baseContent else colors.mutedContent,
            )
        },
    )
}

@Composable
private fun OrganizationRow(organization: OrganizationSummaryOut) {
    val roleLabel = organization.role
        ?.replace('_', ' ')
        ?.replaceFirstChar { it.uppercaseChar() }
        ?: "Member"
    val members = if (organization.memberCount == 1) "1 member" else "${organization.memberCount} members"
    DListItem(
        title = organization.displayName,
        subtitle = "$roleLabel • $members",
        leading = { DAvatar(initials = organization.displayName, size = 24.dp) },
    )
}

@Composable
private fun FriendSections(servers: List<ServerUi>, ui: AppUiState) {
    val colors = LocalKalendeeColors.current
    if (servers.isEmpty()) {
        DText(
            text = "Sign in to a server to see friends.",
            style = DType.sm,
            color = colors.mutedContent,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        return
    }
    servers.forEach { server ->
        val serverId = server.account.profile.id
        if (servers.size > 1) {
            ServerSubheader(name = server.account.profile.name, connected = true)
        }
        val friends = ui.friendsByServer[serverId]
        val error = ui.socialErrors[serverId]
        when {
            friends == null && error != null -> {
                DText(
                    text = error,
                    style = DType.xs,
                    color = colors.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            friends == null -> {
                if (ui.socialLoading) LoadingRow(label = "Loading friends…")
            }

            friends.incoming.isEmpty() && friends.friends.isEmpty() -> {
                DText(
                    text = "No friends yet.",
                    style = DType.sm,
                    color = colors.mutedContent,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            else -> {
                friends.incoming.forEach { request ->
                    DListItem(
                        title = request.user.displayName,
                        subtitle = "@${request.user.username}",
                        leading = { DAvatar(initials = request.user.displayName, size = 28.dp) },
                        trailing = {
                            DBadge(
                                text = "Request",
                                color = DBadgeColor.Warning,
                                size = DBadgeSize.Xs,
                            )
                        },
                    )
                }
                friends.friends.forEach { friend ->
                    DListItem(
                        title = friend.displayName,
                        subtitle = "@${friend.username}",
                        leading = { DAvatar(initials = friend.displayName, size = 28.dp) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SidebarSectionHeader(
    icon: ImageVector,
    title: String,
    count: Int? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = dimens.space3, end = dimens.space1, top = dimens.space3, bottom = dimens.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DIcon(icon = icon, size = 14.dp, tint = colors.mutedContent)
        DText(
            text = title.uppercase(),
            style = DType.sectionLabel,
            color = colors.mutedContent,
            modifier = Modifier.padding(start = 6.dp).weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null && count > 0) {
            DBadge(text = count.toString(), size = DBadgeSize.Xs)
        }
        trailing?.invoke(this)
    }
}

@Composable
private fun GroupHeading(title: String) {
    val colors = LocalKalendeeColors.current
    DText(
        text = title,
        style = DType.xs.medium(),
        color = colors.subtleContent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 6.dp, bottom = 2.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ServerSubheader(name: String, connected: Boolean) {
    val colors = LocalKalendeeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DText(
            text = name,
            style = DType.xs.semibold(),
            color = colors.mutedContent,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!connected) {
            DIcon(icon = Lucide.WifiOff, size = 12.dp, tint = colors.error)
        }
    }
}

@Composable
private fun ServerError(server: ServerUi) {
    val message = server.error ?: return
    val colors = LocalKalendeeColors.current
    DText(
        text = message,
        style = DType.xs,
        color = colors.error,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun LoadingRow(label: String) {
    val colors = LocalKalendeeColors.current
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DSpinner(size = 14.dp, strokeWidth = 2.dp)
        DText(text = label, style = DType.xs, color = colors.mutedContent)
    }
}

private data class OrganizationGroup(
    val organization: OrganizationSummaryOut,
    val calendars: List<CalendarUi>,
)

private data class CalendarGrouping(
    val personal: List<CalendarUi>,
    val organizations: List<OrganizationGroup>,
    val ungroupedOrganizations: List<OrganizationSummaryOut>,
)

/**
 * Buckets a server's calendars into personal and per-organization groups.
 *
 * A calendar belongs to the organization group when its `organizationId` matches
 * an organization returned by `organizations()`; otherwise (no org, or an org the
 * viewer cannot see) it lands under Personal. Every calendar appears exactly once.
 */
private fun groupCalendars(
    calendars: List<CalendarUi>,
    organizations: List<OrganizationSummaryOut>,
): CalendarGrouping {
    val buckets = organizations.associate { it.id to mutableListOf<CalendarUi>() }
    val personal = mutableListOf<CalendarUi>()
    calendars.forEach { calendar ->
        val organizationId = calendar.calendar.organizationId?.value
        val bucket = organizationId?.let { buckets[it] }
        if (bucket != null) bucket += calendar else personal += calendar
    }
    val groups = organizations.mapNotNull { organization ->
        buckets.getValue(organization.id)
            .takeIf { it.isNotEmpty() }
            ?.let { OrganizationGroup(organization, it) }
    }
    val groupedIds = groups.map { it.organization.id }.toSet()
    return CalendarGrouping(
        personal = personal,
        organizations = groups,
        ungroupedOrganizations = organizations.filterNot { it.id in groupedIds },
    )
}
