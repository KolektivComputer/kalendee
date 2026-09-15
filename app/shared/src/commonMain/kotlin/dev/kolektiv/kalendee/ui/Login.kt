package dev.kolektiv.kalendee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kolektiv.kalendee.client.KalendeeClient
import dev.kolektiv.kalendee.client.SessionStore
import kotlinx.coroutines.launch

@Composable
fun LoginPane(
    sessions: SessionStore,
    onLocal: () -> Unit,
    onSignedIn: () -> Unit,
) {
    val colors = KalendeeTheme.colors
    val scope = rememberCoroutineScope()
    val client = remember(sessions) { KalendeeClient(sessions) }
    var server by remember { mutableStateOf("http://127.0.0.1:8080") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.base100)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            "Kalendee",
            style = TextStyle(color = colors.baseContent, fontSize = 28.sp, fontWeight = FontWeight.Light),
        )
        BasicText(
            "Sign in to a server, or keep this calendar on the device.",
            style = TextStyle(color = colors.baseContent.copy(alpha = 0.6f), fontSize = 14.sp),
        )
        Field("Server", server, colors) { server = it }
        Field("Username", username, colors) { username = it }
        Field("Password", password, colors) { password = it }
        error?.let { BasicText(it, style = TextStyle(color = colors.error, fontSize = 13.sp)) }
        Chip("Sign in", colors.primary, colors.primaryContent) {
            if (busy) return@Chip
            busy = true
            error = null
            scope.launch {
                runCatching { client.login(server, username, password) }
                    .onSuccess { onSignedIn() }
                    .onFailure { error = it.message ?: "Could not sign in" }
                busy = false
            }
        }
        Chip("Use locally", colors.base200, colors.baseContent) {
            sessions.setLocalOnly(true)
            onLocal()
        }
    }
}

@Composable
private fun Field(label: String, value: String, colors: KalendeeColors, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(label, style = TextStyle(color = colors.baseContent.copy(alpha = 0.55f), fontSize = 12.sp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = colors.baseContent, fontSize = 16.sp),
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.base200, RoundedCornerShape(4.dp))
                .padding(12.dp),
        )
    }
}

@Composable
private fun Chip(label: String, background: Color, content: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        BasicText(label, style = TextStyle(color = content, fontSize = 15.sp, fontWeight = FontWeight.Medium))
    }
}
