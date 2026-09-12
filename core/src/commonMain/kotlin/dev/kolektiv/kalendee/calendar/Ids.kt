package dev.kolektiv.kalendee.calendar

import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class CalendarId(val value: String) {
    companion object {
        fun generate(): CalendarId = CalendarId(Uuid.random().toString())

        fun parse(raw: String): CalendarId {
            val parsed = Uuid.parseOrNull(raw)
                ?: throw CalendarException.Invalid("invalid calendar id")
            return CalendarId(parsed.toString())
        }
    }
}

@Serializable
@JvmInline
value class EventId(val value: String) {
    companion object {
        fun generate(): EventId = EventId(Uuid.random().toString())

        fun parse(raw: String): EventId {
            val parsed = Uuid.parseOrNull(raw)
                ?: throw CalendarException.Invalid("invalid event id")
            return EventId(parsed.toString())
        }
    }
}

@Serializable
@JvmInline
value class OrganizationId(val value: String) {
    companion object {
        fun generate(): OrganizationId = OrganizationId(Uuid.random().toString())

        fun parse(raw: String): OrganizationId {
            val parsed = Uuid.parseOrNull(raw)
                ?: throw CalendarException.Invalid("invalid organization id")
            return OrganizationId(parsed.toString())
        }
    }
}
