package dev.kolektiv.kalendee.storage

data class StoredObject(
    val bytes: ByteArray,
    val contentType: String,
)

/**
 * Generic byte-object storage used by the server for user uploadables such as
 * profile pictures, with room for future attachments.
 *
 * Backed by either the local filesystem ([LocalObjectStorage]) or any
 * S3-compatible service ([S3ObjectStorage]). Cloudflare R2 is supported through
 * the S3-compatible backend: set `storage.s3.endpoint` to the R2 endpoint,
 * `storage.s3.bucket` to the bucket, and `storage.s3.pathStyle = true`.
 */
interface ObjectStorage {
    suspend fun put(key: String, bytes: ByteArray, contentType: String)

    suspend fun get(key: String): StoredObject?

    suspend fun delete(key: String)
}
