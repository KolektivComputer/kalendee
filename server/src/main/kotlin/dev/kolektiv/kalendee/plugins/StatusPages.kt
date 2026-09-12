package dev.kolektiv.kalendee.plugins

import dev.kolektiv.kalendee.api.ErrorBody
import dev.kolektiv.kalendee.calendar.CalendarException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.SerializationException

fun Application.configureStatusPages(development: Boolean) {
    install(StatusPages) {
        exception<CalendarException.NotFound> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ErrorBody(error = "not_found", message = cause.message ?: "not found"),
            )
        }
        exception<CalendarException.Invalid> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorBody(error = "invalid", message = cause.message ?: "invalid"),
            )
        }
        exception<CalendarException.Conflict> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ErrorBody(error = "conflict", message = cause.message ?: "conflict"),
            )
        }
        exception<CalendarException.PreconditionFailed> { call, cause ->
            call.respond(
                HttpStatusCode.PreconditionFailed,
                ErrorBody(error = "precondition_failed", message = cause.message ?: "precondition failed"),
            )
        }
        exception<CalendarException.Unauthorized> { call, cause ->
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorBody(error = "unauthorized", message = cause.message ?: "unauthorized"),
            )
        }
        exception<CalendarException.Forbidden> { call, cause ->
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorBody(error = "forbidden", message = cause.message ?: "forbidden"),
            )
        }
        exception<CalendarException.TooManyRequests> { call, cause ->
            call.respond(
                HttpStatusCode.TooManyRequests,
                ErrorBody(error = "too_many_requests", message = cause.message ?: "too many requests"),
            )
        }
        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorBody(error = "invalid", message = cause.message ?: "invalid request"),
            )
        }
        exception<SerializationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorBody(error = "invalid", message = cause.message ?: "invalid json"),
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorBody(error = "invalid", message = cause.message ?: "invalid"),
            )
        }
        exception<Throwable> { call, cause ->
            if (development) {
                call.respondText(
                    text = developmentErrorPage(call.request.httpMethod.value, call.request.path(), cause),
                    contentType = ContentType.Text.Html,
                    status = HttpStatusCode.InternalServerError,
                )
            } else {
                call.application.log.error("Unhandled server error", cause)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorBody(error = "internal_error", message = "internal server error"),
                )
            }
        }
    }
}

private fun developmentErrorPage(method: String, path: String, cause: Throwable): String = """
    <!doctype html>
    <html lang="en">
    <head>
    <meta charset="utf-8">
    <title>Kalendee development error</title>
    <style>
    body { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; margin: 0; background: #1b1f24; color: #e6edf3; }
    .banner { padding: 12px 16px; background: #7f1d1d; color: #fee2e2; font-weight: bold; }
    main { padding: 16px; }
    h1 { font-size: 1.2rem; margin: 0 0 4px; }
    .message { color: #fca5a5; margin: 0 0 12px; }
    .request { color: #9ca3af; margin: 0 0 16px; }
    pre { padding: 12px; background: #0d1117; border: 1px solid #30363d; overflow-x: auto; white-space: pre-wrap; }
    </style>
    </head>
    <body>
    <div class="banner">Kalendee development error — set app.development=false to disable</div>
    <main>
    <h1>${escapeHtml(cause.javaClass.name)}</h1>
    <p class="message">${escapeHtml(cause.message ?: "(no message)")}</p>
    <p class="request">${escapeHtml(method)} ${escapeHtml(path)}</p>
    <pre>${escapeHtml(cause.stackTraceToString())}</pre>
    </main>
    </body>
    </html>
""".trimIndent()

private fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
