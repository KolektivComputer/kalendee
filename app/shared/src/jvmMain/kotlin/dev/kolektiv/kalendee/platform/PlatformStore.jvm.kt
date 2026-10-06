package dev.kolektiv.kalendee.platform

import dev.kolektiv.kalendee.client.KeyValueStore
import java.util.prefs.Preferences

private class PreferencesKeyValueStore : KeyValueStore {
    private val prefs: Preferences = Preferences.userRoot().node(NodePath)

    override fun getString(key: String): String? = runCatching { prefs.get(key, null) }.getOrNull()

    override fun putString(key: String, value: String) {
        runCatching {
            prefs.put(key, value)
            prefs.flush()
        }
    }

    override fun remove(key: String) {
        runCatching {
            prefs.remove(key)
            prefs.flush()
        }
    }

    private companion object {
        const val NodePath = "dev/kolektiv/kalendee"
    }
}

actual fun platformKeyValueStore(): KeyValueStore = PreferencesKeyValueStore()
