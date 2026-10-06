package dev.kolektiv.kalendee.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.kolektiv.kalendee.platform.platformKeyValueStore
import kotlinx.coroutines.launch

/**
 * Creates the app-wide [AppState], loads persisted servers/preferences exactly once
 * and kicks off the initial refresh and reminder planning.
 */
@Composable
fun rememberAppState(): AppState {
    val store = remember { platformKeyValueStore() }
    val state = remember { AppState(store) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) {
        state.loadPersisted()
        scope.launch { state.refreshAll() }
        scope.launch { state.refreshReminders() }
    }
    return state
}
