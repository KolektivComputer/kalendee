package dev.kolektiv.kalendee.storage

data class StoredAvatar(
    val bytes: ByteArray,
    val contentType: String,
)

interface AvatarStorage {
    suspend fun put(key: String, bytes: ByteArray, contentType: String)

    suspend fun get(key: String): StoredAvatar?

    suspend fun delete(key: String)
}
