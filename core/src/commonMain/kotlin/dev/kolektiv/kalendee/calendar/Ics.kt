package dev.kolektiv.kalendee.calendar

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

data class ParsedIcsEvent(
    val uid: String,
    val title: String,
    val notes: String,
    val location: String,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
)

data class ParsedIcs(
    val name: String,
    val events: List<ParsedIcsEvent>,
)

/**
 * RFC 5545 text unfolding + VEVENT extract for ICS/webcal subscriptions (#6).
 * Time zones other than Zulu are treated as UTC wall-clock; hosts can refine later.
 */
fun parseIcs(text: String): ParsedIcs {
    val lines = unfoldIcs(text)
    var name = "Subscribed"
    val events = mutableListOf<ParsedIcsEvent>()
    var current: MutableMap<String, String>? = null
    for (line in lines) {
        when (line) {
            "BEGIN:VEVENT" -> current = mutableMapOf()
            "END:VEVENT" -> {
                current?.let { eventFromProps(it)?.let(events::add) }
                current = null
            }
            else -> {
                val split = line.indexOf(':')
                if (split < 0) continue
                val keyPart = line.substring(0, split)
                val value = unescapeIcs(line.substring(split + 1))
                val key = keyPart.substringBefore(';').uppercase()
                val bucket = current
                if (bucket == null) {
                    if (key == "X-WR-CALNAME" || key == "NAME") name = value.ifBlank { name }
                    continue
                }
                bucket[key] = value
                if (key == "DTSTART") bucket["__dtstartRaw"] = line.substring(split + 1)
                if (key == "DTEND") bucket["__dtendRaw"] = line.substring(split + 1)
                if (keyPart.uppercase().contains("VALUE=DATE")) {
                    if (key == "DTSTART") bucket["__startAllDay"] = "1"
                    if (key == "DTEND") bucket["__endAllDay"] = "1"
                }
            }
        }
    }
    return ParsedIcs(name = name, events = events)
}

fun assertPublicHttpUrl(raw: String): String {
    var input = raw.trim()
    if (input.startsWith("webcal://", ignoreCase = true)) {
        input = "https://" + input.substring("webcal://".length)
    }
    val match = Regex("^(https?)://([^/]+)").find(input)
        ?: throw CalendarException.Invalid("Enter a valid http(s) or webcal URL.")
    val host = match.groupValues[2].substringBefore(':').substringBefore('/').lowercase()
        .removePrefix("[").removeSuffix("]")
    if (isBlockedHost(host)) {
        throw CalendarException.Invalid("That host cannot be fetched.")
    }
    return input
}

internal fun unfoldIcs(text: String): List<String> {
    val raw = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val lines = mutableListOf<String>()
    for (line in raw) {
        if ((line.startsWith(" ") || line.startsWith("\t")) && lines.isNotEmpty()) {
            lines[lines.lastIndex] = lines.last() + line.drop(1)
        } else if (line.isNotEmpty()) {
            lines += line
        }
    }
    return lines
}

private fun unescapeIcs(value: String): String =
    value.replace("\\n", "\n", ignoreCase = true)
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\\\", "\\")

private fun eventFromProps(props: Map<String, String>): ParsedIcsEvent? {
    val startRaw = props["__dtstartRaw"] ?: props["DTSTART"] ?: return null
    val startAllDay = props["__startAllDay"] == "1" || isDateOnly(startRaw)
    val start = icsDateToInstant(startRaw, startAllDay) ?: return null
    val endRaw = props["__dtendRaw"] ?: props["DTEND"] ?: props["DTSTART"] ?: startRaw
    val endAllDay = props["__endAllDay"] == "1" || isDateOnly(endRaw)
    var end = icsDateToInstant(endRaw, endAllDay) ?: start
    if (end <= start) {
        end = Instant.fromEpochMilliseconds(
            start.toEpochMilliseconds() + if (startAllDay) 86_400_000 else 3_600_000,
        )
    }
    val uid = props["UID"]?.trim().orEmpty().ifBlank { "ics-$start-${props["SUMMARY"] ?: "event"}" }
    return ParsedIcsEvent(
        uid = uid,
        title = props["SUMMARY"]?.trim().orEmpty().ifBlank { "Untitled" },
        notes = props["DESCRIPTION"]?.trim().orEmpty(),
        location = props["LOCATION"]?.trim().orEmpty(),
        start = start,
        end = end,
        allDay = startAllDay,
    )
}

private fun isDateOnly(raw: String): Boolean {
    val value = if (':' in raw) raw.substringAfterLast(':') else raw
    return Regex("^\\d{8}$").matches(value.trim())
}

private fun icsDateToInstant(raw: String, allDay: Boolean): Instant? {
    val value = (if (':' in raw) raw.substringAfterLast(':') else raw).trim()
    if (Regex("^\\d{8}$").matches(value)) {
        val date = runCatching {
            LocalDate.parse("${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)}")
        }.getOrNull() ?: return null
        return date.atStartOfDayIn(TimeZone.UTC)
    }
    val match = Regex("^(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})(Z)?$").find(value) ?: return null
    val iso = "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}T" +
        "${match.groupValues[4]}:${match.groupValues[5]}:${match.groupValues[6]}" +
        if (match.groupValues[7].isNotEmpty()) "Z" else ""
    if (allDay) {
        val date = runCatching {
            LocalDate.parse("${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}")
        }.getOrNull() ?: return null
        return date.atStartOfDayIn(TimeZone.UTC)
    }
    return runCatching { Instant.parse(if (iso.endsWith("Z")) iso else iso + "Z") }.getOrNull()
}

private fun isBlockedHost(host: String): Boolean {
    if (host == "localhost" || host == "::1" || host == "0.0.0.0") return true
    if (host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localdomain")) {
        return true
    }
    if (Regex("^(\\d{1,3}\\.){3}\\d{1,3}$").matches(host)) {
        val parts = host.split('.').map { it.toInt() }
        val a = parts[0]
        val b = parts[1]
        if (a == 10 || a == 127 || a == 0) return true
        if (a == 169 && b == 254) return true
        if (a == 172 && b in 16..31) return true
        if (a == 192 && b == 168) return true
        if (a == 100 && b in 64..127) return true
    }
    if (':' in host && (host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80"))) return true
    return false
}
