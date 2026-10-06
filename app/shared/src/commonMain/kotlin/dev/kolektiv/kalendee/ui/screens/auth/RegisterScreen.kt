package dev.kolektiv.kalendee.ui.screens.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
fun RegisterScreen(state: AppState, serverId: String) {
    val ui by state.state.collectAsState()
    val scope = rememberCoroutineScope()
    val server = ui.servers.firstOrNull { it.account.profile.id == serverId }
    var username by remember(serverId) { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    PageColumn {
        ScreenTitle(
            title = "Register",
            subtitle = server?.account?.profile?.name ?: serverId,
            onBack = { state.navigator.pop() },
        )
        if (server == null) {
            InlineError("This server is no longer configured.")
            return@PageColumn
        }
        if (info != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Text(
                    text = info.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { state.navigator.pop() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Back to servers")
            }
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
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                error = null
            },
            label = { Text("Email (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        InlineError(error)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (username.isBlank() || password.isEmpty()) {
                    error = "Enter a username and password."
                    return@Button
                }
                error = null
                submitting = true
                scope.launch {
                    try {
                        val result = state.register(
                            serverId = serverId,
                            username = username.trim(),
                            password = password,
                            email = email.trim().ifBlank { null },
                        )
                        if (state.isSignedIn(serverId)) {
                            state.navigator.replaceRoot(Route.Calendar)
                        } else {
                            info = when {
                                result.verificationRequired ->
                                    "Check your email to verify your account, then sign in."
                                result.user != null ->
                                    "Account created. Sign in to continue."
                                else -> "Registration submitted. Sign in once the server confirms your account."
                            }
                        }
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
            Text(if (submitting) "Registering…" else "Create account")
        }
        if (submitting) {
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        }
    }
}
