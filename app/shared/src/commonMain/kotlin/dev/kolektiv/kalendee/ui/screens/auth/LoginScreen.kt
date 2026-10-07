package dev.kolektiv.kalendee.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.ui.AppState
import dev.kolektiv.kalendee.ui.components.InlineError
import dev.kolektiv.kalendee.ui.components.PageColumn
import dev.kolektiv.kalendee.ui.components.ScreenTitle
import dev.kolektiv.kalendee.ui.components.messageOf
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.components.DButton
import dev.kolektiv.kalendee.ui.design.components.DErrorAlert
import dev.kolektiv.kalendee.ui.design.components.DLink
import dev.kolektiv.kalendee.ui.design.components.DPasswordField
import dev.kolektiv.kalendee.ui.design.components.DTextField
import dev.kolektiv.kalendee.ui.nav.Route
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(state: AppState, serverId: String) {
    val ui by state.state.collectAsState()
    val colors = LocalKalendeeColors.current
    val scope = rememberCoroutineScope()
    val server = ui.servers.firstOrNull { it.account.profile.id == serverId }
    var username by remember(serverId) { mutableStateOf(server?.account?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var usernameError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
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
        DTextField(
            value = username,
            onValueChange = {
                username = it
                usernameError = null
                error = null
            },
            label = "Username",
            placeholder = "ada",
            error = usernameError,
            contentType = ContentType.Username,
        )
        Spacer(Modifier.height(12.dp))
        DPasswordField(
            value = password,
            onValueChange = {
                password = it
                passwordError = null
                error = null
            },
            label = "Password",
            error = passwordError,
        )
        error?.let {
            Spacer(Modifier.height(12.dp))
            DErrorAlert(text = it)
        }
        Spacer(Modifier.height(16.dp))
        DButton(
            onClick = {
                usernameError = if (username.isBlank()) "Enter your username." else null
                passwordError = if (password.isEmpty()) "Enter your password." else null
                if (usernameError != null || passwordError != null) return@DButton
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
            loading = submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            DText("Sign in")
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DText(
                text = "New to this server? ",
                style = DType.sm,
                color = colors.mutedContent,
            )
            DLink(
                text = "Create an account",
                onClick = { state.navigator.push(Route.Register(serverId)) },
            )
        }
        Spacer(Modifier.height(8.dp))
        DText(
            text = "The session is stored only on this device.",
            style = DType.xs,
            color = colors.mutedContent,
        )
    }
}
