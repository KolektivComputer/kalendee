package dev.kolektiv.kalendee.storage

import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest

class S3ObjectStorage(
    settings: S3Settings,
    private val client: S3Client = buildClient(settings),
) : ObjectStorage {
    private val bucket = settings.bucket

    override suspend fun put(key: String, bytes: ByteArray, contentType: String) {
        withContext(Dispatchers.IO) {
            client.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .build(),
                RequestBody.fromBytes(bytes),
            )
        }
    }

    override suspend fun get(key: String): StoredObject? = withContext(Dispatchers.IO) {
        try {
            val response = client.getObjectAsBytes(
                GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build(),
            )
            StoredObject(
                bytes = response.asByteArray(),
                contentType = response.response().contentType()?.takeIf { it.isNotBlank() }
                    ?: "application/octet-stream",
            )
        } catch (_: NoSuchKeyException) {
            null
        }
    }

    override suspend fun delete(key: String) {
        withContext(Dispatchers.IO) {
            client.deleteObject(
                DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build(),
            )
        }
    }

    companion object {
        fun buildClient(settings: S3Settings): S3Client {
            val builder = S3Client.builder()
                .region(Region.of(settings.region))
                .forcePathStyle(settings.pathStyle)
                // AWS SDK v2 >= 2.30 defaults to WHEN_SUPPORTED, which makes
                // PutObject stream the body with an aws-chunked trailer
                // (x-amz-checksum-crc32). S3-compatible stores that do not
                // implement trailing checksums - notably Cloudflare R2 - reject
                // that request with 403 AccessDenied. Only send checksums when
                // the operation requires them, matching pre-2.30 behavior.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            if (settings.endpoint.isNotBlank()) {
                builder.endpointOverride(URI.create(settings.endpoint))
            }
            if (settings.accessKey != null || settings.secretKey != null) {
                builder.credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(settings.accessKey.orEmpty(), settings.secretKey.orEmpty()),
                    ),
                )
            }
            return builder.build()
        }
    }
}
