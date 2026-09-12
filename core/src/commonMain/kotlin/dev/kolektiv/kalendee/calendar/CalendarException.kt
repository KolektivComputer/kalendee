package dev.kolektiv.kalendee.calendar

sealed class CalendarException(message: String) : RuntimeException(message) {
    class Invalid(message: String) : CalendarException(message)
    class NotFound(message: String) : CalendarException(message)
    class Conflict(message: String) : CalendarException(message)
    class PreconditionFailed(message: String) : CalendarException(message)
    class Unauthorized(message: String) : CalendarException(message)
    class Forbidden(message: String) : CalendarException(message)
    class TooManyRequests(message: String) : CalendarException(message)
}
