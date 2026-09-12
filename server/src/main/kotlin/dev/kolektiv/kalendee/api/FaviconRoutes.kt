package dev.kolektiv.kalendee.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private const val FaviconResource = "static/favicon.svg"
private const val FaviconCacheControl = "public, max-age=86400"

internal fun isFaviconPath(path: String): Boolean = path == "/favicon.svg" || path == "/favicon.ico"

private object Favicon {
    val bytes: ByteArray? by lazy {
        Favicon::class.java.classLoader.getResourceAsStream(FaviconResource)?.use { it.readBytes() }
    }
}

fun Route.faviconRoutes() {
    get("/favicon.svg") { call.respondFavicon() }
    get("/favicon.ico") { call.respondFavicon() }
}

private suspend fun ApplicationCall.respondFavicon() {
    val bytes = Favicon.bytes
    if (bytes == null) {
        respond(HttpStatusCode.NotFound)
        return
    }
    val etag = "\"${bytes.size}-${bytes.contentHashCode()}\""
    response.header(HttpHeaders.CacheControl, FaviconCacheControl)
    response.header(HttpHeaders.ETag, etag)
    if (etagMatches(request.headers[HttpHeaders.IfNoneMatch], etag)) {
        respond(HttpStatusCode.NotModified)
        return
    }
    respondBytes(bytes, ContentType.Image.SVG)
}

private fun etagMatches(header: String?, etag: String): Boolean =
    header?.split(',')?.any { it.trim() == etag || it.trim() == "*" } == true
