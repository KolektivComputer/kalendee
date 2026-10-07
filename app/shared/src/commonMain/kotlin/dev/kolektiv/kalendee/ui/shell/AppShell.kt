package dev.kolektiv.kalendee.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.ui.AppState
import dev.kolektiv.kalendee.ui.AppUiState
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens
import dev.kolektiv.kalendee.ui.design.components.DBadge
import dev.kolektiv.kalendee.ui.design.components.DBadgeColor
import dev.kolektiv.kalendee.ui.design.components.DBadgeSize
import dev.kolektiv.kalendee.ui.design.components.DDrawer
import dev.kolektiv.kalendee.ui.design.components.DIconButton
import dev.kolektiv.kalendee.ui.design.semibold
import dev.kolektiv.kalendee.ui.icons.Lucide
import dev.kolektiv.kalendee.ui.nav.Route

/** Web `lg` breakpoint: below this the sidebar becomes a drawer. */
private val SidebarBreakpoint = 1024.dp
private val SidebarWidth = 280.dp
private val TopBarHeight = 48.dp

/**
 * Responsive app shell.
 *
 * Wide windows (>= 1024dp) show the persistent 280dp sidebar next to the content,
 * matching the web `lg:drawer-open` layout; narrower windows show a top bar with
 * a hamburger that opens the same sidebar as a [DDrawer]. There is no bottom
 * navigation bar.
 */
@Composable
fun AppShell(
    state: AppState,
    route: Route,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val ui by state.state.collectAsState()
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    var drawerOpen by remember { mutableStateOf(false) }

    fun navigate(target: Route) {
        when (target) {
            Route.Calendar, Route.Upcoming, Route.Settings, Route.Servers -> {
                if (state.navigator.current != target) state.navigator.replaceRoot(target)
            }

            else -> state.navigator.push(target)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(colors.base100)) {
        val wide = maxWidth >= SidebarBreakpoint
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(
                ui = ui,
                showMenu = !wide,
                onMenu = { drawerOpen = true },
                onHome = { navigate(if (ui.servers.isEmpty()) Route.Servers else Route.Calendar) },
                onUpcoming = { navigate(Route.Upcoming) },
            )
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (wide) {
                    Box(modifier = Modifier.width(SidebarWidth).fillMaxHeight()) {
                        CalendarSidebar(
                            state = state,
                            current = route,
                            onNavigate = { target -> navigate(target) },
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(dimens.border)
                                .fillMaxHeight()
                                .background(colors.base300),
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    content()
                }
            }
        }
        if (!wide) {
            DDrawer(open = drawerOpen, onDismiss = { drawerOpen = false }) {
                CalendarSidebar(
                    state = state,
                    current = route,
                    onNavigate = { target ->
                        drawerOpen = false
                        navigate(target)
                    },
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    ui: AppUiState,
    showMenu: Boolean,
    onMenu: () -> Unit,
    onHome: () -> Unit,
    onUpcoming: () -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    val unread = ui.notifications.count { !it.notification.read }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TopBarHeight)
            .background(colors.base100)
            .drawBehind {
                val stroke = dimens.border.toPx()
                drawLine(
                    color = colors.base300,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .padding(horizontal = dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showMenu) {
            DIconButton(
                icon = Lucide.Menu,
                contentDescription = "Open navigation",
                onClick = onMenu,
            )
        }
        DText(
            text = "Kalendee",
            style = DType.lg.semibold(),
            modifier = Modifier
                .clip(dimens.fieldShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onHome,
                )
                .padding(horizontal = dimens.space2, vertical = dimens.space2),
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        Box {
            DIconButton(
                icon = Lucide.Bell,
                contentDescription = "Upcoming notifications",
                onClick = onUpcoming,
            )
            if (unread > 0) {
                DBadge(
                    text = if (unread > 9) "9+" else unread.toString(),
                    color = DBadgeColor.Error,
                    size = DBadgeSize.Xs,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}
