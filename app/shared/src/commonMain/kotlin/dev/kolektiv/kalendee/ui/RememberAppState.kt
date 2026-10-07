package dev.kolektiv.kalendee.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.kolektiv.kalendee.platform.platformKeyValueStore
import kotlinx.coroutines.launch

/**
 * Creates the app-wide [AppState], loads persisted servers/preferences exactly once
 * and kicks off the initial refresh, reminder planning, social and notification
 * loads. All initial loads are independent so one failing server cannot block the
 * others.
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
        scope.launch { state.refreshSocial() }
        scope.launch { state.refreshNotifications() }
    }
    return state
}
