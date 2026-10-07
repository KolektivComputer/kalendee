package dev.kolektiv.kalendee.ui.screens.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.ui.AppState
import dev.kolektiv.kalendee.ui.ServerUi
import dev.kolektiv.kalendee.ui.components.InlineError
import dev.kolektiv.kalendee.ui.components.Notice
import dev.kolektiv.kalendee.ui.components.PageColumn
import dev.kolektiv.kalendee.ui.components.ScreenTitle
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.components.DButton
import dev.kolektiv.kalendee.ui.design.components.DButtonVariant
import dev.kolektiv.kalendee.ui.design.components.DCard
import dev.kolektiv.kalendee.ui.design.components.DIcon
import dev.kolektiv.kalendee.ui.design.components.DListSection
import dev.kolektiv.kalendee.ui.design.components.DSpinner
import dev.kolektiv.kalendee.ui.design.components.DSwitch
import dev.kolektiv.kalendee.ui.design.semibold
import dev.kolektiv.kalendee.ui.icons.Lucide
import dev.kolektiv.kalendee.ui.nav.Route
import kotlinx.coroutines.launch

@Composable
fun ServersScreen(state: AppState) {
    val ui by state.state.collectAsState()
    val stack by state.navigator.stack.collectAsState()
    val scope = rememberCoroutineScope()
    val isRoot = stack.size <= 1

    PageColumn {
        ScreenTitle(
            title = "Servers",
            subtitle = if (ui.servers.isEmpty()) null else "${ui.servers.size} configured",
            onBack = if (isRoot) null else ({ state.navigator.pop() }),
        )
        Notice(text = ui.notice, onDismiss = state::clearNotice)

        if (ui.servers.isEmpty()) {
            EmptyServersCard(onConnect = { state.navigator.push(Route.AddServer) })
        } else {
            DButton(
                onClick = { state.navigator.replaceRoot(Route.Calendar) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                DText("Open calendar")
            }
            Spacer(Modifier.height(12.dp))
            DListSection(label = "Configured") {
                ui.servers.forEach { server ->
                    ServerRow(
                        server = server,
                        busy = ui.loading,
                        onOpen = { state.navigator.push(Route.ServerDetail(server.account.profile.id)) },
                        onToggleEnabled = { enabled ->
                            scope.launch { state.setServerEnabled(server.account.profile.id, enabled) }
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            DButton(
                onClick = { state.navigator.push(Route.AddServer) },
                variant = DButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
                enabled = !ui.loading,
            ) {
                DText("Add server")
            }
            if (ui.loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    DSpinner(size = 20.dp)
                }
            }
        }
    }
}

@Composable
private fun EmptyServersCard(onConnect: () -> Unit) {
    val colors = LocalKalendeeColors.current
    DCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DIcon(icon = Lucide.Globe, size = 40.dp, tint = colors.primary)
            Spacer(Modifier.height(12.dp))
            DText(text = "Connect a server", style = DType.xl.semibold())
            Spacer(Modifier.height(4.dp))
            DText(
                text = "Kalendee is self-hosted. Point the app at your server to load calendars, " +
                    "friends, and notifications.",
                style = DType.sm,
                color = colors.mutedContent,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            DButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                DText("Connect a server")
            }
        }
    }
}

@Composable
private fun ServerRow(
    server: ServerUi,
    busy: Boolean,
    onOpen: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    val colors = LocalKalendeeColors.current
    DCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpen,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                DText(
                    text = server.account.profile.name,
                    style = DType.base.semibold(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DText(
                    text = server.account.profile.baseUrl,
                    style = DType.xs,
                    color = colors.mutedContent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DIcon(
                icon = Lucide.ChevronRight,
                tint = colors.mutedContent,
                size = 16.dp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            DSwitch(
                checked = server.account.profile.enabled,
                onCheckedChange = onToggleEnabled,
                enabled = !busy,
            )
        }
        Spacer(Modifier.height(6.dp))
        DText(
            text = if (server.username != null) {
                "Signed in as ${server.username}"
            } else {
                "Signed out — tap to sign in"
            },
            style = DType.sm,
            color = if (server.username != null) colors.baseContent else colors.mutedContent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when (server.online) {
            false -> DText(
                text = "Offline",
                style = DType.xs,
                color = colors.error,
                modifier = Modifier.padding(top = 2.dp),
            )

            null -> DText(
                text = "Not checked yet",
                style = DType.xs,
                color = colors.mutedContent,
                modifier = Modifier.padding(top = 2.dp),
            )

            true -> Unit
        }
        InlineError(server.error)
    }
}
