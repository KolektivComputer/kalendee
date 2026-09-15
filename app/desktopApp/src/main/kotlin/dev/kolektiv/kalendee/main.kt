package dev.kolektiv.kalendee

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Kalendee",
        state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        App()
    }
}
