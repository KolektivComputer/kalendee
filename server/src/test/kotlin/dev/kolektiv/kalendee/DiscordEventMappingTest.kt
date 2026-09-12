package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.oauth.discord.DiscordOccurrenceCap
import dev.kolektiv.kalendee.oauth.discord.UnsupportedRecurrenceNote
import dev.kolektiv.kalendee.oauth.discord.UntitledEventTitle
import dev.kolektiv.kalendee.oauth.discord.discordEventUid
import dev.kolektiv.kalendee.oauth.discord.mapDiscordEvent
import dev.kolektiv.kalendee.oauth.providers.DiscordEntityMetadata
import dev.kolektiv.kalendee.oauth.providers.DiscordRecurrenceRule
import dev.kolektiv.kalendee.oauth.providers.DiscordRecurrenceRuleNWeekday
import dev.kolektiv.kalendee.oauth.providers.DiscordScheduledEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class DiscordEventMappingTest {
    @Test
    fun singleEventMapsDiscordMetadataAndOneHourFallback() {
        val event = discordEvent(start = "2026-09-12T15:00:00Z")

        val mapped = mapDiscordEvent(event, guildName = "Kolektiv", now = now).single()

        assertEquals("discord:guild-1:event-1", mapped.uid)
        assertEquals("Weekly standup", mapped.title)
        assertEquals(Instant.parse("2026-09-12T15:00:00Z"), mapped.start)
        assertEquals(Instant.parse("2026-09-12T16:00:00Z"), mapped.end)
        assertEquals(EventStatus.CONFIRMED, mapped.status)
        assertEquals("Voice Lounge", mapped.location)
        assertFalse(mapped.allDay)
        val description = assertNotNull(mapped.description)
        assertTrue(description.startsWith("Sync"), description)
        assertTrue(description.contains("Discord event: https://discord.com/events/guild-1/event-1"), description)
        assertTrue(description.contains("Guild: Kolektiv"), description)
        assertTrue(description.contains("7 interested"), description)
    }

    @Test
    fun missingOptionalFieldsAreTolerated() {
        val event = discordEvent(
            name = "   ",
            description = null,
            entityType = DiscordScheduledEvent.ENTITY_TYPE_VOICE,
            location = null,
            userCount = null,
        )

        val mapped = mapDiscordEvent(event, guildName = null, now = now).single()

        assertEquals(UntitledEventTitle, mapped.title)
        assertNull(mapped.location)
        assertEquals(
            "Discord event: https://discord.com/events/guild-1/event-1",
            mapped.description,
        )
    }

    @Test
    fun canceledMasterMapsToCancelledStatus() {
        val event = discordEvent(status = DiscordScheduledEvent.STATUS_CANCELED)

        assertEquals(EventStatus.CANCELLED, mapDiscordEvent(event, "Kolektiv", now).single().status)
    }

    @Test
    fun invalidScheduledStartSkipsTheEvent() {
        val event = discordEvent(start = "not-an-instant")

        assertTrue(mapDiscordEvent(event, "Kolektiv", now).isEmpty())
    }

    @Test
    fun dailyRuleExpandsWithinTheMaterializationWindow() {
        val event = discordEvent(
            start = "2026-09-10T15:00:00Z",
            rule = DiscordRecurrenceRule(
                start = "2026-09-10T15:00:00Z",
                frequency = 3,
                interval = 1,
            ),
        )

        val mapped = mapDiscordEvent(event, "Kolektiv", now)

        assertEquals(182, mapped.size)
        assertEquals(Instant.parse("2026-09-10T15:00:00Z"), mapped.first().start)
        assertEquals(Instant.parse("2027-03-10T15:00:00Z"), mapped.last().start)
        assertTrue(mapped.all { it.start <= windowEnd })
        assertTrue(mapped.all { "discord:guild-1:event-1:" in it.uid })
    }

    @Test
    fun recurringSeriesAnchorsAtRuleStartNotScheduledStart() {
        val event = discordEvent(
            start = "2026-09-12T15:00:00Z",
            rule = DiscordRecurrenceRule(
                start = "2026-09-09T15:00:00Z",
                frequency = 3,
                interval = 2,
            ),
        )

        val starts = mapDiscordEvent(event, "Kolektiv", now).take(4).map { it.start }

        assertEquals(
            listOf(
                "2026-09-09T15:00:00Z",
                "2026-09-11T15:00:00Z",
                "2026-09-13T15:00:00Z",
                "2026-09-15T15:00:00Z",
            ).map(Instant::parse),
            starts,
        )
    }

    @Test
    fun dailyKnownWeekdaySetsAreSupported() {
        val knownSets = listOf(
            listOf(0, 1, 2, 3, 4),
            listOf(1, 2, 3, 4, 5),
            listOf(6, 0, 1, 2, 3),
            listOf(4, 5),
            listOf(5, 6),
            listOf(6, 0),
        )
        for (weekdays in knownSets) {
            val event = discordEvent(
                start = "2026-09-10T09:00:00Z",
                rule = DiscordRecurrenceRule(
                    start = "2026-09-10T09:00:00Z",
                    frequency = 3,
                    interval = 1,
                    byWeekday = weekdays,
                ),
            )
            val mapped = mapDiscordEvent(event, "Kolektiv", now)
            assertTrue(mapped.isNotEmpty(), "weekday set $weekdays produced no occurrences")
            assertTrue(
                mapped.none { it.description?.contains(UnsupportedRecurrenceNote) == true },
                "weekday set $weekdays must be treated as supported",
            )
        }
    }

    @Test
    fun dailyKnownWeekdaySetFiltersDays() {
        val event = discordEvent(
            start = "2026-09-11T09:00:00Z",
            rule = DiscordRecurrenceRule(
                start = "2026-09-11T09:00:00Z",
                frequency = 3,
                interval = 1,
                byWeekday = listOf(0, 1, 2, 3, 4),
            ),
        )

        val starts = mapDiscordEvent(event, "Kolektiv", now).take(5).map { it.start }

        assertEquals(
            listOf(
                "2026-09-11T09:00:00Z",
                "2026-09-14T09:00:00Z",
                "2026-09-15T09:00:00Z",
                "2026-09-16T09:00:00Z",
                "2026-09-17T09:00:00Z",
            ).map(Instant::parse),
            starts,
        )
    }

    @Test
    fun dailyUnknownWeekdaySetFallsBackToTheMaster() {
        val event = discordEvent(
            start = "2026-09-12T15:00:00Z",
            rule = DiscordRecurrenceRule(
                start = "2026-09-11T09:00:00Z",
                frequency = 3,
                interval = 1,
                byWeekday = listOf(2, 4),
            ),
        )

        val mapped = mapDiscordEvent(event, "Kolektiv", now).single()

        assertEquals("discord:guild-1:event-1", mapped.uid)
        assertEquals(Instant.parse("2026-09-12T15:00:00Z"), mapped.start)
        assertTrue(assertNotNull(mapped.description).contains(UnsupportedRecurrenceNote))
    }

    @Test
    fun fallbackRuleStartInTheFutureMovesTheEvent() {
        val event = discordEvent(
            start = "2026-09-12T15:00:00Z",
            rule = DiscordRecurrenceRule(
                start = "2026-10-01T09:00:00Z",
                frequency = 42,
            ),
        )

        val mapped = mapDiscordEvent(event, "Kolektiv", now).single()

        assertEquals(Instant.parse("2026-10-01T09:00:00Z"), mapped.start)
        assertEquals(Instant.parse("2026-10-01T10:00:00Z"), mapped.end)
        assertTrue(assertNotNull(mapped.description).contains(UnsupportedRecurrenceNote))
    }

    @Test
    fun unsupportedRuleWithBrokenDatesFallsBackWithoutThrowing() {
        val event = discordEvent(
            rule = DiscordRecurrenceRule(
                start = "not-an-instant",
                end = "also-not-an-instant",
                frequency = 2,
                interval = 1,
            ),
        )

        val mapped = mapDiscordEvent(event, "Kolektiv", now).single()

        assertEquals("discord:guild-1:event-1", mapped.uid)
        assertEquals(Instant.parse("2026-09-12T15:00:00Z"), mapped.start)
        assertTrue(assertNotNull(mapped.description).contains(UnsupportedRecurrenceNote))
    }

    @Test
    fun weeklySingleWeekdayExpandsOnInterval() {
        val event = discordEvent(
            start = "2026-09-09T10:00:00Z",
            rule = DiscordRecurrenceRule(
                start = "2026-09-09T10:00:00Z",
                frequency = 2,
                interval = 2,
                byWeekday = listOf(2),
            ),
        )

        val mapped = mapDiscordEvent(event, "Kolektiv", now)

        assertEquals(14, mapped.size)
        assertEquals(
            listOf(
                "2026-09-09T10:00:00Z",
                "2026-09-23T10:00:00Z",
                "2026-10-07T10:00:00Z",
            ).map(Instant::parse),
            mapped.take(3).map { it.start },
        )
        assertEquals(Instant.parse("2027-03-10T10:00:00Z"), mapped.last().start)
    }

    @Test
    fun weeklyMultipleWeekdaysFallBack() {
        val event = discordEvent(
            rule = DiscordRecurrenceRule(
                start = "2026-09-09T10:00:00Z",
                frequency = 2,
                interval = 1,
                byWeekday = listOf(1, 3),
            ),
        )

        assertFallback(event)
    }

    @Test
    fun weeklyIntervalAboveTwoFallsBack() {
        val event = discordEvent(
            rule = DiscordRecurrenceRule(
                start = "2026-09-09T10:00:00Z",
                frequency = 2,
                interval = 3,
                byWeekday = listOf(2),
            ),
        )

        assertFallback(event)
    }

    @Test
    fun monthlyNthWeekdayExpands() {
        val event = discordEvent(
            start = "2026-09-07T18:00:00Z",
            rule = DiscordRecurrenceRule(
                start = "2026-09-07T18:00:00Z",
                frequency = 1,
                interval = 1,
                byNWeekday = listOf(DiscordRecurrenceRuleNWeekday(n = 1, day = 0)),
            ),
        )

        val mapped = mapDiscordEvent(event, "Kolektiv", now)

        assertEquals(7, mapped.size)
        assertEquals(
            listOf(
                "2026-09-07T18:00:00Z",
                "2026-10-05T18:00:00Z",
                "2026-11-02T18:00:00Z",
                "2026-12-07T18:00:00Z",
                "2027-01-04T18:00:00Z",
                "2027-02-01T18:00:00Z",
                "2027-03-01T18:00:00Z",
            ).map(Instant::parse),
            mapped.map { it.start },
        )
    }

    @Test
    fun monthlyLastWeekdayUsesFifth() {
        val event = discordEvent(
            start = "2026-09-25T18:00:00Z",
            rule = DiscordRecurrenceRule(
                start = "2026-09-25T18:00:00Z",
                frequency = 1,
                interval = 1,
                byNWeekday = listOf(DiscordRecurrenceRuleNWeekday(n = 5, day = 4)),
            ),
        )

        val mapped = mapDiscordEvent(event, "Kolektiv", now)

        assertEquals(6, mapped.size)
        assertEquals(Instant.parse("2026-09-25T18:00:00Z"), mapped.first().start)
        assertEquals(Instant.parse("2026-10-30T18:00:00Z"), mapped[1].start)
        assertEquals(Instant.parse("2027-02-26T18:00:00Z"), mapped.last().start)
    }

    @Test
    fun monthlyMalformedNthWeekdayFallsBack() {
        val event = discordEvent(
            rule = DiscordRecurrenceRule(
                start = "2026-09-07T18:00:00Z",
                frequency = 1,
                interval = 1,
                byNWeekday = listOf(DiscordRecurrenceRuleNWeekday(n = 6, day = 0)),
            ),
        )

        assertFallback(event)
    }

    @Test
    fun yearlyMonthAndDayExpands() {
        val event = discordEvent(
            start = "2026-03-12T08:00:00Z",
            rule = DiscordRecurrenceRule(
                start = "2026-03-12T08:00:00Z",
                frequency = 0,
                interval = 1,
                byMonth = listOf(9),
                byMonthDay = listOf(12),
            ),
        )

        val mapped = mapDiscordEvent(event, "Kolektiv", now).single()

        assertEquals(Instant.parse("2026-09-12T08:00:00Z"), mapped.start)
    }

    @Test
    fun yearlyWithoutMonthAndDayFallsBack() {
        val event = discordEvent(
            rule = DiscordRecurrenceRule(
                start = "2026-03-12T08:00:00Z",
                frequency = 0,
                interval = 1,
            ),
        )

        assertFallback(event)
    }

    @Test
    fun windowExcludesOccurrencesOlderThanThirtyDaysAndBeyondHorizon() {
        val event = discordEvent(
            start = "2026-01-01T15:00:00Z",
            rule = DiscordRecurrenceRule(
                start = "2026-01-01T15:00:00Z",
                frequency = 3,
                interval = 1,
            ),
        )

        val mapped = mapDiscordEvent(event, "Kolektiv", now)

        assertEquals(210, mapped.size)
        assertEquals(Instant.parse("2026-08-13T15:00:00Z"), mapped.first().start)
        assertEquals(Instant.parse("2027-03-10T15:00:00Z"), mapped.last().start)
        assertTrue(mapped.size <= DiscordOccurrenceCap)
    }

    @Test
    fun ruleEndStopsOccurrences() {
        val event = discordEvent(
            start = "2026-09-10T15:00:00Z",
            rule = DiscordRecurrenceRule(
                start = "2026-09-10T15:00:00Z",
                end = "2026-09-14T15:00:00Z",
                frequency = 3,
                interval = 1,
            ),
        )

        val mapped = mapDiscordEvent(event, "Kolektiv", now)

        assertEquals(5, mapped.size)
        assertEquals(Instant.parse("2026-09-14T15:00:00Z"), mapped.last().start)
    }

    @Test
    fun ruleCountStopsOccurrences() {
        val event = discordEvent(
            start = "2026-09-10T15:00:00Z",
            rule = DiscordRecurrenceRule(
                start = "2026-09-10T15:00:00Z",
                frequency = 3,
                interval = 1,
                count = 3,
            ),
        )

        assertEquals(3, mapDiscordEvent(event, "Kolektiv", now).size)
    }

    @Test
    fun recurringEventsWithoutEndUseOneHourOccurrences() {
        val event = discordEvent(
            start = "2026-09-10T15:00:00Z",
            end = null,
            rule = DiscordRecurrenceRule(
                start = "2026-09-10T15:00:00Z",
                frequency = 3,
                interval = 1,
                count = 3,
            ),
        )

        val mapped = mapDiscordEvent(event, "Kolektiv", now)

        assertTrue(mapped.all { it.end - it.start == 1.hours })
    }

    @Test
    fun canceledRecurringMasterKeepsCancelledStatusOnOccurrences() {
        val event = discordEvent(
            status = DiscordScheduledEvent.STATUS_CANCELED,
            start = "2026-09-10T15:00:00Z",
            rule = DiscordRecurrenceRule(
                start = "2026-09-10T15:00:00Z",
                frequency = 3,
                interval = 1,
                count = 2,
            ),
        )

        val mapped = mapDiscordEvent(event, "Kolektiv", now)

        assertEquals(2, mapped.size)
        assertTrue(mapped.all { it.status == EventStatus.CANCELLED })
    }

    private fun assertFallback(event: DiscordScheduledEvent) {
        val mapped = mapDiscordEvent(event, "Kolektiv", now).single()
        assertEquals(discordEventUid(event.guildId, event.id), mapped.uid)
        assertTrue(assertNotNull(mapped.description).contains(UnsupportedRecurrenceNote))
    }

    private fun discordEvent(
        id: String = "event-1",
        guildId: String = "guild-1",
        name: String = "Weekly standup",
        description: String? = "Sync",
        start: String = "2026-09-12T15:00:00Z",
        end: String? = null,
        status: Int = DiscordScheduledEvent.STATUS_SCHEDULED,
        entityType: Int = DiscordScheduledEvent.ENTITY_TYPE_EXTERNAL,
        location: String? = "Voice Lounge",
        userCount: Int? = 7,
        rule: DiscordRecurrenceRule? = null,
    ): DiscordScheduledEvent = DiscordScheduledEvent(
        id = id,
        guildId = guildId,
        channelId = null,
        name = name,
        description = description,
        scheduledStartTime = start,
        scheduledEndTime = end,
        privacyLevel = 2,
        status = status,
        entityType = entityType,
        entityMetadata = location?.let { DiscordEntityMetadata(location = it) },
        userCount = userCount,
        recurrenceRule = rule,
        creator = null,
        creatorId = null,
        image = null,
    )

    private companion object {
        val now: Instant = Instant.parse("2026-09-12T12:00:00Z")
        val windowEnd: Instant = Instant.parse("2027-03-11T12:00:00Z")
    }
}
