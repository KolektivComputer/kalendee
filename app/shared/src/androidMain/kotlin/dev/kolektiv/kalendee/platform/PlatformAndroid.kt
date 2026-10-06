package dev.kolektiv.kalendee.platform

import android.content.Context
import android.content.SharedPreferences
import dev.kolektiv.kalendee.client.KeyValueStore

/**
 * Process-wide Android application context holder for platform-backed services.
 *
 * It must be set from the app entry point (`MainActivity`) before any call to
 * [platformKeyValueStore] or `platformNotificationScheduler`, both of which are
 * invoked during composition / app startup.
 */
object KalendeeAndroid {
    lateinit var appContext: Context
}

internal const val SHARED_PREFS_NAME = "kalendee"

actual fun platformKeyValueStore(): KeyValueStore = AndroidKeyValueStore(
    KalendeeAndroid.appContext.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
)

private class AndroidKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
