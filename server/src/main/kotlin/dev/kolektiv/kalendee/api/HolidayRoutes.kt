package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCustomHoliday
import dev.kolektiv.kalendee.calendar.CustomHoliday
import dev.kolektiv.kalendee.web.HolidayCatalogItem
import dev.kolektiv.kalendee.web.holidayCatalog
import dev.kolektiv.kalendee.web.toState
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class HolidayPrefsBody(
    val showHolidays: Boolean? = null,
    val subscribedIds: List<String>? = null,
)

@Serializable
data class HolidaysResponse(
    val showHolidays: Boolean,
    val subscribedIds: List<String>,
    val custom: List<CustomHoliday>,
    val catalog: List<HolidayCatalogItem>,
)

fun Route.holidayRoutes(store: CalendarStore) {
    route("/holidays") {
        get {
            val prefs = store.holidayPrefs(call.user().id)
            call.respond(
                HolidaysResponse(
                    showHolidays = prefs.showHolidays,
                    subscribedIds = prefs.subscribedIds,
                    custom = prefs.custom,
                    catalog = holidayCatalog(),
                ),
            )
        }
        put {
            val body = call.receive<HolidayPrefsBody>()
            val user = call.user()
            body.showHolidays?.let { store.setShowHolidays(user.id, it) }
            body.subscribedIds?.let { store.setHolidaySubscriptions(user.id, it) }
            call.respond(store.holidayPrefs(user.id).toState())
        }
        post("/custom") {
            val created = store.createCustomHoliday(call.user().id, call.receive<CreateCustomHoliday>())
            call.respond(HttpStatusCode.Created, created)
        }
        delete("/custom/{id}") {
            val id = call.parameters["id"] ?: throw CalendarException.Invalid("missing holiday id")
            if (!store.deleteCustomHoliday(id, call.user().id)) {
                throw CalendarException.NotFound("holiday not found")
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
