package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.storage.StorageSettings
import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StorageSettingsTest {
    @Test
    fun defaultsToBlankPublicBaseUrl() {
        val settings = StorageSettings.from(MapApplicationConfig())
        assertEquals("", settings.publicBaseUrl)
        assertNull(settings.publicUrl("avatars/1/a.png"))
    }

    @Test
    fun trimsTrailingSlashFromPublicBaseUrl() {
        val settings = StorageSettings.from(
            MapApplicationConfig("storage.publicBaseUrl" to "https://cdn.example.com/"),
        )
        assertEquals("https://cdn.example.com", settings.publicBaseUrl)
    }

    @Test
    fun blankPublicBaseUrlStaysBlank() {
        val settings = StorageSettings.from(
            MapApplicationConfig("storage.publicBaseUrl" to "   "),
        )
        assertEquals("", settings.publicBaseUrl)
        assertNull(settings.publicUrl("avatars/1/a.png"))
    }

    @Test
    fun publicUrlJoinsBaseAndKey() {
        val settings = StorageSettings.from(
            MapApplicationConfig("storage.publicBaseUrl" to "https://cdn.example.com"),
        )
        assertEquals("https://cdn.example.com/avatars/1/a.png", settings.publicUrl("avatars/1/a.png"))
        assertEquals("https://cdn.example.com/avatars/1/a.png", settings.publicUrl("/avatars/1/a.png"))
    }

    @Test
    fun keepsExistingLocalDirAndS3Fields() {
        val settings = StorageSettings.from(
            MapApplicationConfig(
                "storage.localDir" to "/data/avatars",
                "storage.s3.enabled" to "true",
                "storage.s3.endpoint" to "https://r2.example.com",
                "storage.s3.bucket" to "kalendee",
                "storage.s3.pathStyle" to "true",
            ),
        )
        assertEquals("/data/avatars", settings.localDir)
        assertEquals(true, settings.s3.enabled)
        assertEquals("https://r2.example.com", settings.s3.endpoint)
        assertEquals("kalendee", settings.s3.bucket)
        assertEquals(true, settings.s3.pathStyle)
    }
}
