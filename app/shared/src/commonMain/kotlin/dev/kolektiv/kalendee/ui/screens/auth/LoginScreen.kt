package dev.kolektiv.kalendee.ui.screens.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.ui.AppState
import dev.kolektiv.kalendee.ui.components.InlineError
import dev.kolektiv.kalendee.ui.components.PageColumn
import dev.kolektiv.kalendee.ui.components.ScreenTitle
import dev.kolektiv.kalendee.ui.components.messageOf
import dev.kolektiv.kalendee.ui.nav.Route
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(state: AppState, serverId: String) {
    val ui by state.state.collectAsState()
    val scope = rememberCoroutineScope()
    val server = ui.servers.firstOrNull { it.account.profile.id == serverId }
    var username by remember(serverId) { mutableStateOf(server?.account?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    PageColumn {
        ScreenTitle(
            title = "Sign in",
            subtitle = server?.account?.profile?.name ?: serverId,
            onBack = { state.navigator.pop() },
        )
        if (server == null) {
            InlineError("This server is no longer configured.")
            return@PageColumn
        }
        OutlinedTextField(
            value = username,
            onValueChange = {
                username = it
                error = null
            },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                error = null
            },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        InlineError(error)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (username.isBlank() || password.isEmpty()) {
                    error = "Enter your username and password."
                    return@Button
                }
                error = null
                submitting = true
                scope.launch {
                    try {
                        state.login(serverId, username.trim(), password)
                        state.navigator.replaceRoot(Route.Calendar)
                    } catch (e: Exception) {
                        error = messageOf(e)
                    } finally {
                        submitting = false
                    }
                }
            },
            enabled = !submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (submitting) "Signing in…" else "Sign in")
        }
        if (submitting) {
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "The session is stored only on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
