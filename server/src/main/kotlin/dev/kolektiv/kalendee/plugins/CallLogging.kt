package dev.kolektiv.kalendee.plugins

import dev.kolektiv.kalendee.api.isFaviconPath
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.event.Level

private const val PackAssetPathPrefix = "/__keel/pack/"

fun Application.configureCallLogging() {
    install(CallLogging) {
        level = Level.INFO
        filter { call ->
            val path = call.request.path()
            !path.startsWith(PackAssetPathPrefix) && !isFaviconPath(path)
        }
        format { call ->
            val status = call.response.status()?.value ?: "-"
            "${call.request.httpMethod.value} ${call.request.path()} -> $status"
        }
    }
}
