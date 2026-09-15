package dev.kolektiv.kalendee

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.kolektiv.kalendee.ui.KalendeeApp
import dev.kolektiv.kalendee.ui.KalendeeTheme

@Composable
@Preview
fun App() {
    KalendeeTheme {
        KalendeeApp()
    }
}
