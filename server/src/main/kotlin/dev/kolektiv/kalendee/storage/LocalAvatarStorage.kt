package dev.kolektiv.kalendee.storage

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalAvatarStorage(localDir: String) : AvatarStorage {
    private val root: Path = Path.of(localDir).toAbsolutePath().normalize()

    override suspend fun put(key: String, bytes: ByteArray, contentType: String) {
        withContext(Dispatchers.IO) {
            val target = resolve(key)
            target.parent?.createDirectories()
            target.writeBytes(bytes)
            typePath(key).writeText(contentType)
        }
    }

    override suspend fun get(key: String): StoredAvatar? = withContext(Dispatchers.IO) {
        val target = resolve(key)
        if (!target.isRegularFile()) return@withContext null
        val contentType = typePath(key)
            .takeIf { it.exists() }
            ?.readText()
            ?.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"
        StoredAvatar(bytes = target.readBytes(), contentType = contentType)
    }

    override suspend fun delete(key: String) {
        withContext(Dispatchers.IO) {
            resolve(key).deleteIfExists()
            typePath(key).deleteIfExists()
        }
    }

    private fun resolve(key: String): Path {
        require(key.isNotBlank() && !key.contains("..") && !Path.of(key).isAbsolute) {
            "invalid avatar key"
        }
        val target = root.resolve(key).normalize()
        require(target.startsWith(root)) { "invalid avatar key" }
        return target
    }

    private fun typePath(key: String): Path = resolve("$key.type")
}
