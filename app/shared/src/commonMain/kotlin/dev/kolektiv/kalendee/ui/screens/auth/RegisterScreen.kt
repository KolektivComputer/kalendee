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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
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
import dev.kolektiv.kalendee.ui.design.components.DInfoAlert
import dev.kolektiv.kalendee.ui.design.components.DLink
import dev.kolektiv.kalendee.ui.design.components.DPasswordField
import dev.kolektiv.kalendee.ui.design.components.DTextField
import dev.kolektiv.kalendee.ui.nav.Route
import kotlinx.coroutines.launch

@Composable
fun RegisterScreen(state: AppState, serverId: String) {
    val ui by state.state.collectAsState()
    val colors = LocalKalendeeColors.current
    val scope = rememberCoroutineScope()
    val server = ui.servers.firstOrNull { it.account.profile.id == serverId }
    var username by remember(serverId) { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var usernameError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
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
            DInfoAlert(text = info.orEmpty())
            Spacer(Modifier.height(16.dp))
            DButton(
                onClick = { state.navigator.pop() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                DText("Back to servers")
            }
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
        Spacer(Modifier.height(12.dp))
        DTextField(
            value = email,
            onValueChange = {
                email = it
                error = null
            },
            label = "Email (optional)",
            placeholder = "ada@example.com",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            contentType = ContentType.EmailAddress,
        )
        error?.let {
            Spacer(Modifier.height(12.dp))
            DErrorAlert(text = it)
        }
        Spacer(Modifier.height(16.dp))
        DButton(
            onClick = {
                usernameError = if (username.isBlank()) "Choose a username." else null
                passwordError = if (password.isEmpty()) "Choose a password." else null
                if (usernameError != null || passwordError != null) return@DButton
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

                                else ->
                                    "Registration submitted. Sign in once the server confirms your account."
                            }
                        }
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
            DText("Create account")
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DText(
                text = "Already registered? ",
                style = DType.sm,
                color = colors.mutedContent,
            )
            DLink(
                text = "Sign in",
                onClick = { state.navigator.pop() },
            )
        }
    }
}
