package dev.kolektiv.kalendee.ui

data class CalendarUi(
    val id: String,
    val name: String,
    val color: String,
    val hidden: Boolean = false,
)

data class EventUi(
    val id: String,
    val calendarId: String,
    val title: String,
    val notes: String = "",
    val location: String = "",
    val startEpochMs: Long,
    val endEpochMs: Long,
    val allDay: Boolean = false,
)

enum class CalendarView { Day, Week, Month }
