package dev.kolektiv.kalendee.storage

import io.ktor.server.config.ApplicationConfig

data class StorageSettings(
    val localDir: String,
    val publicBaseUrl: String,
    val s3: S3Settings,
) {
    /**
     * Absolute public URL for [key], or null when no [publicBaseUrl] is
     * configured. Used to redirect object reads at a CDN/R2 domain instead of
     * proxying bytes through the server.
     */
    fun publicUrl(key: String): String? {
        if (publicBaseUrl.isBlank()) return null
        return publicBaseUrl.trimEnd('/') + "/" + key.trimStart('/')
    }

    companion object {
        fun from(config: ApplicationConfig): StorageSettings = StorageSettings(
            localDir = config.propertyOrNull("storage.localDir")?.getString()?.takeIf { it.isNotBlank() }
                ?: "build/avatars",
            publicBaseUrl = config.propertyOrNull("storage.publicBaseUrl")?.getString()
                ?.trim()
                ?.trimEnd('/')
                .orEmpty(),
            s3 = S3Settings.from(config),
        )
    }
}

data class S3Settings(
    val enabled: Boolean,
    val endpoint: String,
    val region: String,
    val bucket: String,
    val accessKey: String?,
    val secretKey: String?,
    val pathStyle: Boolean,
) {
    companion object {
        fun from(config: ApplicationConfig): S3Settings = S3Settings(
            enabled = config.propertyOrNull("storage.s3.enabled")?.getString()?.toBooleanStrictOrNull() ?: false,
            endpoint = config.propertyOrNull("storage.s3.endpoint")?.getString().orEmpty(),
            region = config.propertyOrNull("storage.s3.region")?.getString()?.takeIf { it.isNotBlank() }
                ?: "us-east-1",
            bucket = config.propertyOrNull("storage.s3.bucket")?.getString().orEmpty(),
            accessKey = config.propertyOrNull("storage.s3.accessKey")?.getString()?.takeIf { it.isNotBlank() },
            secretKey = config.propertyOrNull("storage.s3.secretKey")?.getString()?.takeIf { it.isNotBlank() },
            pathStyle = config.propertyOrNull("storage.s3.pathStyle")?.getString()?.toBooleanStrictOrNull() ?: true,
        )
    }
}
