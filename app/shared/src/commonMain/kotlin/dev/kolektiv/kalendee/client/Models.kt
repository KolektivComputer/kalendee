package dev.kolektiv.kalendee.client

data class Session(
    val token: String,
    val username: String,
    val displayName: String,
    val serverBase: String,
)

data class ClientCalendar(
    val id: String,
    val name: String,
    val color: String,
    val hidden: Boolean = false,
    val source: String = "local",
)

data class ClientEvent(
    val id: String,
    val calendarId: String,
    val title: String,
    val notes: String = "",
    val location: String = "",
    val startIso: String,
    val endIso: String,
    val allDay: Boolean = false,
    val etag: String,
)

data class SyncMode(val localOnly: Boolean)
