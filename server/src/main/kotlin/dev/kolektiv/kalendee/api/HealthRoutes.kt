package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.calendar.CalendarStore
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.healthRoutes(store: CalendarStore) {
    get("/health") {
        store.ping()
        call.respond(HealthResponse(status = "ok"))
    }
}
