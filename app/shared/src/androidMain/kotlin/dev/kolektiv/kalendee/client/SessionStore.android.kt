package dev.kolektiv.kalendee.client

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

actual class SessionStore actual constructor() {
    actual fun load(): Session? {
        val prefs = prefs() ?: return null
        val token = prefs.getString("token", null) ?: return null
        val server = prefs.getString("server", null) ?: return null
        return Session(
            token = token,
            username = prefs.getString("username", "user") ?: "user",
            displayName = prefs.getString("displayName", "user") ?: "user",
            serverBase = server,
        )
    }

    actual fun save(session: Session) {
        prefs()?.edit()
            ?.putString("token", session.token)
            ?.putString("username", session.username)
            ?.putString("displayName", session.displayName)
            ?.putString("server", session.serverBase)
            ?.apply()
    }

    actual fun clear() {
        prefs()?.edit()?.clear()?.apply()
    }

    actual fun localOnly(): Boolean = prefs()?.getBoolean("localOnly", false) ?: false

    actual fun setLocalOnly(value: Boolean) {
        prefs()?.edit()?.putBoolean("localOnly", value)?.apply()
    }

    companion object {
        @Volatile
        var appContext: Context? = null

        @Volatile
        private var cached: SharedPreferences? = null

        private const val FileName = "kalendee.session"

        private fun prefs(): SharedPreferences? {
            cached?.let { return it }
            val context = appContext ?: return null
            val created = openPrefs(context)
            cached = created
            return created
        }

        private fun openPrefs(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    FileName,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (_: Exception) {
                context.getSharedPreferences(FileName, Context.MODE_PRIVATE)
            }
        }
    }
}
