package dev.kolektiv.kalendee.ui.screens.servers

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import dev.kolektiv.kalendee.ui.AppState
import dev.kolektiv.kalendee.ui.components.PageColumn
import dev.kolektiv.kalendee.ui.components.ScreenTitle
import dev.kolektiv.kalendee.ui.components.messageOf
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.components.DButton
import dev.kolektiv.kalendee.ui.design.components.DErrorAlert
import dev.kolektiv.kalendee.ui.design.components.DTextField
import kotlinx.coroutines.launch

@Composable
fun AddServerScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    val colors = LocalKalendeeColors.current
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    PageColumn {
        ScreenTitle(
            title = "Connect a server",
            subtitle = "Enter the URL of a self-hosted Kalendee server",
            onBack = { state.navigator.pop() },
        )
        DTextField(
            value = url,
            onValueChange = {
                url = it
                error = null
            },
            label = "Server URL",
            placeholder = "https://calendar.example",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            error = null,
        )
        Spacer(Modifier.height(12.dp))
        DTextField(
            value = name,
            onValueChange = { name = it },
            label = "Name (optional)",
            placeholder = "Home",
        )
        Spacer(Modifier.height(8.dp))
        error?.let {
            DErrorAlert(text = it)
            Spacer(Modifier.height(8.dp))
        }
        DText(
            text = "The server must expose the Kalendee API at /api/v1/discovery.",
            style = DType.xs,
            color = colors.mutedContent,
        )
        Spacer(Modifier.height(16.dp))
        DButton(
            onClick = {
                error = null
                adding = true
                scope.launch {
                    try {
                        state.addServer(url, name.trim().ifBlank { null })
                        state.navigator.pop()
                    } catch (e: IllegalArgumentException) {
                        error = e.message ?: "Enter a valid server URL"
                    } catch (e: Exception) {
                        error = messageOf(e)
                    } finally {
                        adding = false
                    }
                }
            },
            loading = adding,
            enabled = url.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            DText("Connect")
        }
    }
}
