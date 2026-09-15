package dev.kolektiv.kalendee.client

expect class SessionStore() {
    fun load(): Session?
    fun save(session: Session)
    fun clear()
    fun localOnly(): Boolean
    fun setLocalOnly(value: Boolean)
}
