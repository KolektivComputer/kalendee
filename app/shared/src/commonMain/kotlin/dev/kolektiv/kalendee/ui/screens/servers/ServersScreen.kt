package dev.kolektiv.kalendee.ui.screens.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.ui.AppState
import dev.kolektiv.kalendee.ui.ServerUi
import dev.kolektiv.kalendee.ui.components.InlineError
import dev.kolektiv.kalendee.ui.components.Notice
import dev.kolektiv.kalendee.ui.components.PageColumn
import dev.kolektiv.kalendee.ui.components.ScreenTitle
import dev.kolektiv.kalendee.ui.components.messageOf
import dev.kolektiv.kalendee.ui.nav.Route
import kotlinx.coroutines.launch

@Composable
fun ServersScreen(state: AppState) {
    val ui by state.state.collectAsState()
    val stack by state.navigator.stack.collectAsState()
    val scope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<ServerUi?>(null) }
    val isRoot = stack.size <= 1

    PageColumn {
        ScreenTitle(
            title = "Servers",
            subtitle = if (ui.servers.isEmpty()) null else "${ui.servers.size} configured",
            onBack = if (isRoot) null else ({ state.navigator.pop() }),
            actions = {
                TextButton(onClick = { showAddDialog = true }, enabled = !ui.loading) {
                    Text("Add server")
                }
            },
        )
        Notice(text = ui.notice, onDismiss = state::clearNotice)

        if (ui.servers.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("No servers yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Add your Kalendee server URL to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { showAddDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Add server")
                    }
                }
            }
        } else {
            Button(
                onClick = { state.navigator.replaceRoot(Route.Calendar) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open calendar")
            }
            Spacer(Modifier.height(12.dp))
            ui.servers.forEach { server ->
                ServerCard(
                    server = server,
                    busy = ui.loading,
                    onToggleEnabled = { enabled ->
                        scope.launch { state.setServerEnabled(server.account.profile.id, enabled) }
                    },
                    onSignIn = { state.navigator.push(Route.Login(server.account.profile.id)) },
                    onRegister = { state.navigator.push(Route.Register(server.account.profile.id)) },
                    onSignOut = {
                        scope.launch { state.logout(server.account.profile.id) }
                    },
                    onRemove = { removeTarget = server },
                    onOpenCalendar = { state.navigator.replaceRoot(Route.Calendar) },
                )
            }
            if (ui.loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }
            OutlinedButton(
                onClick = { scope.launch { state.refreshAll() } },
                enabled = !ui.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (ui.loading) "Refreshing…" else "Refresh all")
            }
        }
    }

    if (showAddDialog) {
        AddServerDialog(state = state, onDismiss = { showAddDialog = false })
    }

    removeTarget?.let { server ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove server?") },
            text = {
                Text(
                    "${server.account.profile.name} and its saved session will be removed " +
                        "from this device. The server itself is not changed.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val serverId = server.account.profile.id
                        removeTarget = null
                        scope.launch { state.removeServer(serverId) }
                    },
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ServerCard(
    server: ServerUi,
    busy: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onSignIn: () -> Unit,
    onRegister: () -> Unit,
    onSignOut: () -> Unit,
    onRemove: () -> Unit,
    onOpenCalendar: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(server.account.profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = server.account.profile.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = server.account.profile.enabled, onCheckedChange = onToggleEnabled)
            }
            Spacer(Modifier.height(8.dp))
            val sessionText = if (server.username != null) {
                "Signed in as ${server.username}"
            } else {
                "Not signed in"
            }
            Text(sessionText, style = MaterialTheme.typography.bodyMedium)
            val onlineText = when (server.online) {
                true -> "Online"
                false -> "Offline"
                null -> "Not checked yet"
            }
            Text(
                text = onlineText,
                style = MaterialTheme.typography.bodySmall,
                color = if (server.online == false) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            InlineError(server.error)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (server.signedIn) {
                    TextButton(onClick = onSignOut, enabled = !busy) { Text("Sign out") }
                } else {
                    TextButton(onClick = onSignIn, enabled = !busy) { Text("Sign in") }
                    TextButton(onClick = onRegister, enabled = !busy) { Text("Register") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRemove, enabled = !busy) { Text("Remove") }
            }
            if (server.signedIn) {
                Button(onClick = onOpenCalendar, modifier = Modifier.fillMaxWidth()) {
                    Text("Open calendar")
                }
            }
        }
    }
}

@Composable
private fun AddServerDialog(state: AppState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!adding) onDismiss() },
        title = { Text("Add server") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    label = { Text("Server URL") },
                    placeholder = { Text("https://calendar.example") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                InlineError(error)
                if (adding) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    error = null
                    adding = true
                    scope.launch {
                        try {
                            state.addServer(url, name)
                            onDismiss()
                        } catch (e: IllegalArgumentException) {
                            error = e.message ?: "Invalid server URL"
                        } catch (e: Exception) {
                            error = messageOf(e)
                        } finally {
                            adding = false
                        }
                    }
                },
                enabled = !adding && url.isNotBlank(),
            ) {
                Text(if (adding) "Checking…" else "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !adding) { Text("Cancel") }
        },
    )
}
