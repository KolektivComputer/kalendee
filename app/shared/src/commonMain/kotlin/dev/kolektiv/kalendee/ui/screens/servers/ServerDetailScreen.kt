package dev.kolektiv.kalendee.ui.screens.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.ui.AppState
import dev.kolektiv.kalendee.ui.components.InlineError
import dev.kolektiv.kalendee.ui.components.PageColumn
import dev.kolektiv.kalendee.ui.components.ScreenTitle
import dev.kolektiv.kalendee.ui.components.SectionTitle
import dev.kolektiv.kalendee.ui.components.messageOf
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.components.DButton
import dev.kolektiv.kalendee.ui.design.components.DButtonVariant
import dev.kolektiv.kalendee.ui.design.components.DCard
import dev.kolektiv.kalendee.ui.design.components.DModal
import dev.kolektiv.kalendee.ui.design.components.DSwitch
import dev.kolektiv.kalendee.ui.design.components.DTextField
import dev.kolektiv.kalendee.ui.design.medium
import dev.kolektiv.kalendee.ui.design.semibold
import dev.kolektiv.kalendee.ui.nav.Route
import kotlinx.coroutines.launch

@Composable
fun ServerDetailScreen(state: AppState, serverId: String) {
    val ui by state.state.collectAsState()
    val server = ui.servers.firstOrNull { it.account.profile.id == serverId }
    val scope = rememberCoroutineScope()

    if (server == null) {
        PageColumn {
            ScreenTitle(title = "Server", onBack = { state.navigator.pop() })
            InlineError("This server is no longer configured.")
        }
        return
    }

    val colors = LocalKalendeeColors.current
    var name by remember(serverId) { mutableStateOf(server.account.profile.name) }
    var renaming by remember { mutableStateOf(false) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var signingOut by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val savedName = server.account.profile.name

    PageColumn {
        ScreenTitle(
            title = savedName,
            subtitle = server.account.profile.baseUrl,
            onBack = { state.navigator.pop() },
        )
        InlineError(actionError)

        SectionTitle("Connection")
        DCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DText(
                    text = "Enabled",
                    style = DType.base.medium(),
                    modifier = Modifier.weight(1f),
                )
                DSwitch(
                    checked = server.account.profile.enabled,
                    onCheckedChange = { enabled ->
                        scope.launch { state.setServerEnabled(serverId, enabled) }
                    },
                )
            }
            val status = when (server.online) {
                true -> "Online"
                false -> "Offline"
                null -> "Not checked yet"
            }
            DText(
                text = status,
                style = DType.sm,
                color = if (server.online == false) colors.error else colors.mutedContent,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        SectionTitle("Name")
        DTextField(
            value = name,
            onValueChange = {
                name = it
                renameError = null
            },
            label = "Server name",
            error = renameError,
        )
        Spacer(Modifier.height(8.dp))
        DButton(
            onClick = {
                scope.launch {
                    renaming = true
                    renameError = null
                    runCatching { state.renameServer(serverId, name) }
                        .onFailure { renameError = messageOf(it) }
                    renaming = false
                }
            },
            loading = renaming,
            enabled = name.trim().isNotEmpty() && name.trim() != savedName,
            variant = DButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
        ) {
            DText("Save name")
        }

        SectionTitle("Session")
        DCard {
            if (server.signedIn) {
                DText(
                    text = "Signed in as ${server.username ?: "unknown"}",
                    style = DType.base.semibold(),
                )
                Spacer(Modifier.height(12.dp))
                DButton(
                    onClick = {
                        scope.launch {
                            signingOut = true
                            actionError = null
                            runCatching { state.logout(serverId) }
                                .onFailure { actionError = messageOf(it) }
                            signingOut = false
                        }
                    },
                    loading = signingOut,
                    variant = DButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DText("Sign out")
                }
            } else {
                DText(
                    text = "Signed out",
                    style = DType.base.semibold(),
                )
                DText(
                    text = "Sign in to load calendars and sync this server.",
                    style = DType.sm,
                    color = colors.mutedContent,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                DButton(
                    onClick = { state.navigator.push(Route.Login(serverId)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DText("Sign in")
                }
                Spacer(Modifier.height(8.dp))
                DButton(
                    onClick = { state.navigator.push(Route.Register(serverId)) },
                    variant = DButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DText("Create account")
                }
            }
        }
        InlineError(server.error)

        Spacer(Modifier.height(16.dp))
        DButton(
            onClick = { state.navigator.replaceRoot(Route.Calendar) },
            modifier = Modifier.fillMaxWidth(),
            enabled = server.signedIn,
        ) {
            DText("Open calendar")
        }
        if (!server.signedIn) {
            DText(
                text = "Sign in to open the calendar.",
                style = DType.xs,
                color = colors.mutedContent,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        DButton(
            onClick = { showRemove = true },
            variant = DButtonVariant.Error,
            modifier = Modifier.fillMaxWidth(),
        ) {
            DText("Remove server")
        }
    }

    if (showRemove) {
        DModal(onDismissRequest = { if (!removing) showRemove = false }) {
            DText(text = "Remove server?", style = DType.xl.semibold())
            Spacer(Modifier.height(8.dp))
            DText(
                text = "$savedName and its saved session will be removed from this device. " +
                    "The server itself is not changed.",
                style = DType.sm,
                color = colors.mutedContent,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DButton(
                    onClick = { showRemove = false },
                    variant = DButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                    enabled = !removing,
                ) {
                    DText("Cancel")
                }
                DButton(
                    onClick = {
                        scope.launch {
                            removing = true
                            actionError = null
                            runCatching { state.removeServer(serverId) }
                                .onFailure { actionError = messageOf(it) }
                            removing = false
                            showRemove = false
                            state.navigator.pop()
                        }
                    },
                    variant = DButtonVariant.Error,
                    loading = removing,
                    modifier = Modifier.weight(1f),
                ) {
                    DText("Remove")
                }
            }
        }
    }
}
