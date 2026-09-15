package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.storage.S3ObjectStorage
import dev.kolektiv.kalendee.storage.S3Settings
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import kotlin.test.Test
import kotlin.test.assertEquals

class S3ObjectStorageTest {
    @Test
    fun sendsChecksumsOnlyWhenRequiredForS3CompatibleEndpoints() {
        val client = S3ObjectStorage.buildClient(
            S3Settings(
                enabled = true,
                endpoint = "https://account.r2.cloudflarestorage.com",
                region = "auto",
                bucket = "kalendee",
                accessKey = "key",
                secretKey = "secret",
                pathStyle = true,
            ),
        )
        client.use {
            val config = it.serviceClientConfiguration()
            assertEquals(
                RequestChecksumCalculation.WHEN_REQUIRED,
                config.requestChecksumCalculation(),
            )
            assertEquals(
                ResponseChecksumValidation.WHEN_REQUIRED,
                config.responseChecksumValidation(),
            )
        }
    }
}
