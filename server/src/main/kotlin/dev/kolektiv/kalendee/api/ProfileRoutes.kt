package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.plugins.currentUser
import dev.kolektiv.kalendee.web.OrganizationActions
import dev.kolektiv.keel.ktor.PageMissingException
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * JSON mirrors of the public web pages `kalendee.profile`, `kalendee.org`,
 * and `kalendee.directory`. Handlers resolve the viewer optionally and
 * delegate to [OrganizationActions], so access follows the same
 * effective-public-access rules as the web UI: a page that would 404 for the
 * viewer becomes a 404 [ErrorBody] here.
 */
fun Route.profileRoutes(actions: OrganizationActions) {
    route("/public/users") {
        get("/{username}") {
            val page = try {
                actions.publicProfilePage(call.parameters["username"].orEmpty(), call.currentUser())
            } catch (_: PageMissingException) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorBody(error = "not_found", message = "profile not found"),
                )
                return@get
            }
            call.respond(page)
        }
    }
    route("/public/orgs") {
        get("/{slug}") {
            val inviteToken = call.request.queryParameters["invite"]
                ?.takeIf { it.isNotBlank() }
                ?: call.request.queryParameters["inviteToken"]?.takeIf { it.isNotBlank() }
            val page = try {
                actions.organizationProfilePage(
                    call.parameters["slug"].orEmpty(),
                    call.currentUser(),
                    inviteToken,
                )
            } catch (_: PageMissingException) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorBody(error = "not_found", message = "organization not found"),
                )
                return@get
            }
            call.respond(page)
        }
    }
    route("/public/directory") {
        get {
            val page = try {
                actions.directoryPage(call.currentUser())
            } catch (_: PageMissingException) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorBody(error = "not_found", message = "directory not found"),
                )
                return@get
            }
            call.respond(page)
        }
    }
}
