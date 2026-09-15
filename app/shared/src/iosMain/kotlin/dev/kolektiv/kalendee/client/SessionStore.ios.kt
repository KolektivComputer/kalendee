package dev.kolektiv.kalendee.client

actual class SessionStore actual constructor() {
    actual fun load(): Session? = memory
    actual fun save(session: Session) { memory = session }
    actual fun clear() { memory = null }
    actual fun localOnly(): Boolean = local
    actual fun setLocalOnly(value: Boolean) { local = value }

    private companion object {
        var memory: Session? = null
        var local: Boolean = false
    }
}
