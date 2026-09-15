package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.groups.GroupService
import dev.kolektiv.kalendee.storage.ObjectStorage
import dev.kolektiv.kalendee.storage.StorageSettings
import dev.kolektiv.kalendee.web.avatarUrl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlin.uuid.Uuid
import kotlinx.io.readByteArray

private const val MaxAvatarBytes = 2 * 1024 * 1024

private val AvatarExtensions = mapOf(
    "image/png" to "png",
    "image/jpeg" to "jpg",
    "image/webp" to "webp",
    "image/gif" to "gif",
)

fun Route.avatarRoutes(
    authService: AuthService,
    groups: GroupService,
    storage: ObjectStorage,
    storageSettings: StorageSettings,
) {
    post("/auth/me/avatar") {
        val user = call.user()
        var bytes: ByteArray? = null
        var contentType: String? = null
        call.receiveMultipart().forEachPart { part ->
            try {
                if (part is PartData.FileItem && part.name == "file" && bytes == null) {
                    val declared = part.contentType?.withoutParameters()?.toString()?.lowercase()
                    if (declared == null || declared !in AvatarExtensions) {
                        throw CalendarException.Invalid("file must be a PNG, JPEG, WebP, or GIF image")
                    }
                    val data = part.provider().readRemaining(MaxAvatarBytes + 1L).readByteArray()
                    if (data.size > MaxAvatarBytes) {
                        throw CalendarException.Invalid("file must be at most 2 MiB")
                    }
                    bytes = data
                    contentType = declared
                }
            } finally {
                part.release()
            }
        }
        val data = bytes
        val type = contentType
        if (data == null || type == null) {
            throw CalendarException.Invalid("file is required")
        }
        val key = "avatars/${user.id.value}/${Uuid.random()}.${AvatarExtensions.getValue(type)}"
        val previous = authService.avatarKey(user.id)
        groups.assertCanStore(user.id, data.size.toLong())
        storage.put(key, data, type)
        val updated = authService.setAvatar(user.id, key, data.size.toLong())
            ?: throw CalendarException.NotFound("user not found")
        if (previous != null && previous != key) {
            storage.delete(previous)
        }
        val version = updated.avatarVersion ?: 0L
        call.respond(AvatarOut(avatarUrl = avatarUrl(user.id, version), version = version))
    }
    delete("/auth/me/avatar") {
        val user = call.user()
        val previous = authService.avatarKey(user.id)
        authService.setAvatar(user.id, null)
        if (previous != null) {
            storage.delete(previous)
        }
        call.respond(HttpStatusCode.NoContent)
    }
    get("/users/{id}/avatar") {
        val id = UserId.parse(call.parameters["id"] ?: throw CalendarException.Invalid("invalid user id"))
        val key = authService.avatarKey(id) ?: throw CalendarException.NotFound("avatar not found")
        val version = authService.userById(id)?.avatarVersion ?: 0L
        val publicUrl = storageSettings.publicUrl(key)
        if (publicUrl != null) {
            call.respondRedirect("$publicUrl?v=$version")
            return@get
        }
        val stored = storage.get(key) ?: throw CalendarException.NotFound("avatar not found")
        val etag = quotedEtag("v$version")
        call.response.header(HttpHeaders.ETag, etag)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
        val ifNoneMatch = parseIfMatch(call.request.headers[HttpHeaders.IfNoneMatch])
        if (ifNoneMatch == "*" || ifNoneMatch == "v$version") {
            call.respond(HttpStatusCode.NotModified)
            return@get
        }
        call.respondBytes(stored.bytes, ContentType.parse(stored.contentType))
    }
}
