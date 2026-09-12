package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.HolidayPrefsBody
import dev.kolektiv.kalendee.api.HolidaysResponse
import dev.kolektiv.kalendee.calendar.CreateCustomHoliday
import dev.kolektiv.kalendee.calendar.CustomHoliday
import dev.kolektiv.kalendee.web.HolidayStateOut
import dev.kolektiv.keel.visit.KeelHeaders
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HolidayApiTest {
    @Test
    fun holidayPrefsStartEmptyAndCanSubscribe() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()

        val initial = client.get("/api/v1/holidays").body<HolidaysResponse>()
        assertEquals(true, initial.showHolidays)
        assertTrue(initial.subscribedIds.isEmpty())
        assertTrue(initial.custom.isEmpty())
        assertTrue(initial.catalog.any { it.id == "us-independence-day" })

        val updated = client.put("/api/v1/holidays") {
            contentType(ContentType.Application.Json)
            setBody(
                HolidayPrefsBody(
                    showHolidays = false,
                    subscribedIds = listOf("us-independence-day", "christmas-day"),
                ),
            )
        }.body<HolidayStateOut>()
        assertEquals(false, updated.showHolidays)
        assertEquals(listOf("christmas-day", "us-independence-day"), updated.subscribedIds.sorted())
    }

    @Test
    fun customHolidaysCanBeAddedAndRemoved() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val created = client.post("/api/v1/holidays/custom") {
            contentType(ContentType.Application.Json)
            setBody(CreateCustomHoliday(title = "Ada Lovelace Day", month = 10, day = 13))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val holiday = created.body<CustomHoliday>()
        assertEquals("Ada Lovelace Day", holiday.title)
        assertEquals(10, holiday.month)
        assertEquals(13, holiday.day)

        val listed = client.get("/api/v1/holidays").body<HolidaysResponse>()
        assertEquals(listOf(holiday.id), listed.custom.map { it.id })

        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/api/v1/holidays/custom/${holiday.id}").status,
        )
        assertTrue(client.get("/api/v1/holidays").body<HolidaysResponse>().custom.isEmpty())
    }

    @Test
    fun unknownCatalogHolidayIsRejected() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val response = client.put("/api/v1/holidays") {
            contentType(ContentType.Application.Json)
            setBody(HolidayPrefsBody(subscribedIds = listOf("not-a-holiday")))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun subscribedHolidayShowsOnHomeWeek() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        client.put("/api/v1/holidays") {
            contentType(ContentType.Application.Json)
            setBody(
                HolidayPrefsBody(
                    showHolidays = true,
                    subscribedIds = listOf("us-independence-day"),
                ),
            )
        }
        val home = client.get("/") {
            url {
                parameters.append("week", "2026-07-04")
                parameters.append("tz", "UTC")
            }
            header(KeelHeaders.VISIT, "true")
        }
        assertTrue(home.bodyAsText().contains("Independence Day"))
        assertTrue(home.bodyAsText().contains("\"calendarId\":\"holiday\""))
    }
}
