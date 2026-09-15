package dev.kolektiv.kalendee.client

import java.util.prefs.Preferences

actual class SessionStore actual constructor() {
    private val prefs = Preferences.userRoot().node("computer.kolektiv.kalendee")

    actual fun load(): Session? {
        val token = prefs.get("token", null) ?: return null
        val server = prefs.get("server", null) ?: return null
        return Session(
            token = token,
            username = prefs.get("username", "user"),
            displayName = prefs.get("displayName", "user"),
            serverBase = server,
        )
    }

    actual fun save(session: Session) {
        prefs.put("token", session.token)
        prefs.put("username", session.username)
        prefs.put("displayName", session.displayName)
        prefs.put("server", session.serverBase)
        prefs.flush()
    }

    actual fun clear() {
        prefs.remove("token")
        prefs.remove("username")
        prefs.remove("displayName")
        prefs.remove("server")
        prefs.flush()
    }

    actual fun localOnly(): Boolean = prefs.getBoolean("localOnly", false)

    actual fun setLocalOnly(value: Boolean) {
        prefs.putBoolean("localOnly", value)
        prefs.flush()
    }
}
