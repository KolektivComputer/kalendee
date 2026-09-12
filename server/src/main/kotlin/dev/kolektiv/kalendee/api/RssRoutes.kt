package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.PublicAccessMode
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.InstantRange
import dev.kolektiv.kalendee.mail.MailService
import dev.kolektiv.kalendee.plugins.currentUser
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

fun Route.rssRoutes(store: CalendarStore, mail: MailService, clock: Clock, auth: AuthService) {
    get("/rss/{token}.xml") {
        val token = call.parameters["token"]?.takeIf { it.isNotBlank() }
            ?: throw CalendarException.Invalid("missing token")
        val calendar = store.publicCalendar(token)
            ?: throw CalendarException.NotFound("calendar not found")
        val viewer = call.currentUser()
        if (auth.effectivePublicAccess(calendar) == PublicAccessMode.SIGNED_IN && viewer == null) {
            throw CalendarException.NotFound("calendar not found")
        }
        if (!auth.canViewPublic(calendar, viewer?.id)) {
            throw CalendarException.NotFound("calendar not found")
        }
        val now = clock.now()
        val events = store.listPublicEvents(calendar.id, InstantRange(start = now, end = now + 90.days))
        call.respondText(
            rssDocument(
                calendar = calendar,
                events = events,
                link = mail.absoluteLink("/c/$token"),
                selfLink = mail.absoluteLink("/rss/$token.xml"),
            ),
            ContentType.parse("application/rss+xml; charset=utf-8"),
        )
    }
}

private fun rssDocument(calendar: Calendar, events: List<Event>, link: String, selfLink: String): String {
    val items = events.joinToString("\n") { event ->
        """
        <item>
          <title>${escapeXml(event.title)}</title>
          <description>${escapeXml(event.description.orEmpty())}</description>
          <location>${escapeXml(event.location.orEmpty())}</location>
          <pubDate>${rfc1123(event.updatedAt)}</pubDate>
          <guid isPermaLink="false">${event.id.value}</guid>
          <event:start>${event.start}</event:start>
          <event:end>${event.end}</event:end>
        </item>
        """.trimIndent()
    }
    val description = calendar.description?.takeIf { it.isNotBlank() } ?: "Public calendar on Kalendee."
    return buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<rss version="2.0" xmlns:event="https://kalendee.dev/ns/event">""")
        appendLine("  <channel>")
        appendLine("    <title>${escapeXml(calendar.displayName)}</title>")
        appendLine("    <description>${escapeXml(description)}</description>")
        appendLine("    <link>${escapeXml(link)}</link>")
        appendLine("""    <atom:link xmlns:atom="http://www.w3.org/2005/Atom" href="${escapeXml(selfLink)}" rel="self" type="application/rss+xml"/>""")
        appendLine("    <language>en</language>")
        if (items.isNotEmpty()) appendLine(items)
        appendLine("  </channel>")
        appendLine("</rss>")
    }
}

private fun rfc1123(instant: Instant): String {
    val zoned = java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(ZoneOffset.UTC)
    return DateTimeFormatter.RFC_1123_DATE_TIME.format(zoned)
}

private fun escapeXml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")
