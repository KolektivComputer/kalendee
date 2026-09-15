package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.mail.MailProvider
import dev.kolektiv.kalendee.mail.MailSettings
import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MailSettingsTest {
    @Test
    fun defaultsToLogProviderAndKeepsExistingDefaults() {
        val settings = MailSettings.from(MapApplicationConfig())
        assertEquals(MailProvider.LOG, settings.provider)
        assertFalse(settings.enabled)
        assertEquals("", settings.host)
        assertEquals(587, settings.port)
        assertEquals("Kalendee <no-reply@localhost>", settings.from)
        assertTrue(settings.startTls)
        assertEquals("", settings.cloudflare.endpoint)
        assertEquals("", settings.cloudflare.token)
        assertEquals(10, settings.cloudflare.timeoutSeconds)
        assertFalse(settings.cloudflare.isComplete)
        assertEquals("", settings.mailgun.apiKey)
        assertEquals("", settings.mailgun.domain)
        assertEquals("us", settings.mailgun.region)
        assertEquals("", settings.mailgun.baseUrl)
        assertEquals(10, settings.mailgun.timeoutSeconds)
        assertFalse(settings.mailgun.isComplete)
        assertEquals("https://api.mailgun.net", settings.mailgun.endpoint)
    }

    @Test
    fun explicitProviderWins() {
        val config = MapApplicationConfig(
            "mail.provider" to "cloudflare",
            "mail.enabled" to "false",
            "mail.cloudflare.endpoint" to "https://mailer.example/send",
        )
        assertEquals(MailProvider.CLOUDFLARE, MailSettings.from(config).provider)
    }

    @Test
    fun providerParsingIsCaseInsensitiveAndTrimmed() {
        assertEquals(MailProvider.SMTP, MailSettings.from(MapApplicationConfig("mail.provider" to " SMTP ")).provider)
        assertEquals(MailProvider.LOG, MailSettings.from(MapApplicationConfig("mail.provider" to "Log")).provider)
        assertEquals(
            MailProvider.CLOUDFLARE,
            MailSettings.from(MapApplicationConfig("mail.provider" to "ClOuDfLaRe")).provider,
        )
        assertEquals(
            MailProvider.MAILGUN,
            MailSettings.from(MapApplicationConfig("mail.provider" to " MailGun ")).provider,
        )
    }

    @Test
    fun explicitMailgunProviderWins() {
        val config = MapApplicationConfig(
            "mail.provider" to "mailgun",
            "mail.enabled" to "false",
            "mail.mailgun.apiKey" to "key-123",
            "mail.mailgun.domain" to "mg.example.com",
        )
        assertEquals(MailProvider.MAILGUN, MailSettings.from(config).provider)
    }

    @Test
    fun enabledWithMailgunCredentialsResolvesMailgun() {
        val config = MapApplicationConfig(
            "mail.enabled" to "true",
            "mail.host" to "",
            "mail.cloudflare.endpoint" to "",
            "mail.mailgun.apiKey" to "key-123",
            "mail.mailgun.domain" to "mg.example.com",
        )
        assertEquals(MailProvider.MAILGUN, MailSettings.from(config).provider)
    }

    @Test
    fun malformedMailgunCredentialsDoNotAutoDetect() {
        val config = MapApplicationConfig(
            "mail.enabled" to "true",
            "mail.host" to "",
            "mail.cloudflare.endpoint" to "",
            "mail.mailgun.apiKey" to "key-123",
            "mail.mailgun.domain" to "",
        )
        assertEquals(MailProvider.LOG, MailSettings.from(config).provider)
    }

    @Test
    fun autoDetectionPrecedenceIsSmtpThenCloudflareThenMailgun() {
        val allSet = MapApplicationConfig(
            "mail.enabled" to "true",
            "mail.host" to "smtp.example.com",
            "mail.cloudflare.endpoint" to "https://mailer.example/send",
            "mail.cloudflare.token" to "secret",
            "mail.mailgun.apiKey" to "key-123",
            "mail.mailgun.domain" to "mg.example.com",
        )
        assertEquals(MailProvider.SMTP, MailSettings.from(allSet).provider)

        val noSmtp = MapApplicationConfig(
            "mail.enabled" to "true",
            "mail.host" to "",
            "mail.cloudflare.endpoint" to "https://mailer.example/send",
            "mail.cloudflare.token" to "secret",
            "mail.mailgun.apiKey" to "key-123",
            "mail.mailgun.domain" to "mg.example.com",
        )
        assertEquals(MailProvider.CLOUDFLARE, MailSettings.from(noSmtp).provider)

        val mailgunOnly = MapApplicationConfig(
            "mail.enabled" to "true",
            "mail.host" to "",
            "mail.cloudflare.endpoint" to "",
            "mail.mailgun.apiKey" to "key-123",
            "mail.mailgun.domain" to "mg.example.com",
        )
        assertEquals(MailProvider.MAILGUN, MailSettings.from(mailgunOnly).provider)
    }

    @Test
    fun derivesMailgunEndpointFromRegion() {
        assertEquals(
            "https://api.mailgun.net",
            MailSettings.from(MapApplicationConfig("mail.mailgun.region" to "us")).mailgun.endpoint,
        )
        assertEquals(
            "https://api.eu.mailgun.net",
            MailSettings.from(MapApplicationConfig("mail.mailgun.region" to "eu")).mailgun.endpoint,
        )
        assertEquals(
            "https://api.eu.mailgun.net",
            MailSettings.from(MapApplicationConfig("mail.mailgun.region" to "EU")).mailgun.endpoint,
        )
        assertEquals(
            "https://api.mailgun.net",
            MailSettings.from(MapApplicationConfig("mail.mailgun.region" to "apac")).mailgun.endpoint,
        )
    }

    @Test
    fun customMailgunBaseUrlWinsOverRegion() {
        val config = MapApplicationConfig(
            "mail.mailgun.region" to "eu",
            "mail.mailgun.baseUrl" to "https://mg.internal.example/",
        )
        assertEquals("https://mg.internal.example", MailSettings.from(config).mailgun.endpoint)
    }

    @Test
    fun parsesMailgunBlockAndTimeout() {
        val config = MapApplicationConfig(
            "mail.mailgun.apiKey" to "key-123",
            "mail.mailgun.domain" to "mg.example.com",
            "mail.mailgun.region" to "eu",
            "mail.mailgun.baseUrl" to "https://custom.example",
            "mail.mailgun.timeoutSeconds" to "42",
        )
        val mailgun = MailSettings.from(config).mailgun
        assertEquals("key-123", mailgun.apiKey)
        assertEquals("mg.example.com", mailgun.domain)
        assertEquals("eu", mailgun.region)
        assertEquals("https://custom.example", mailgun.baseUrl)
        assertEquals(42, mailgun.timeoutSeconds)
        assertTrue(mailgun.isComplete)
    }

    @Test
    fun invalidMailgunTimeoutFallsBackToDefault() {
        val config = MapApplicationConfig(
            "mail.mailgun.timeoutSeconds" to "0",
        )
        assertEquals(10, MailSettings.from(config).mailgun.timeoutSeconds)
    }

    @Test
    fun unknownProviderFallsBackToLog() {
        val settings = MailSettings.from(MapApplicationConfig("mail.provider" to "carrier-pigeon"))
        assertEquals(MailProvider.LOG, settings.provider)
    }

    @Test
    fun enabledWithHostResolvesSmtpViaLegacyPath() {
        val config = MapApplicationConfig(
            "mail.enabled" to "true",
            "mail.host" to "smtp.example.com",
        )
        assertEquals(MailProvider.SMTP, MailSettings.from(config).provider)
    }

    @Test
    fun enabledWithCloudflareEndpointResolvesCloudflare() {
        val config = MapApplicationConfig(
            "mail.enabled" to "true",
            "mail.host" to "",
            "mail.cloudflare.endpoint" to "https://mailer.example/send",
        )
        assertEquals(MailProvider.CLOUDFLARE, MailSettings.from(config).provider)
    }

    @Test
    fun enabledWithoutTransportResolvesLog() {
        val config = MapApplicationConfig(
            "mail.enabled" to "true",
            "mail.host" to "",
            "mail.cloudflare.endpoint" to "",
        )
        assertEquals(MailProvider.LOG, MailSettings.from(config).provider)
    }

    @Test
    fun explicitProviderOverridesLegacyAutoDetection() {
        val config = MapApplicationConfig(
            "mail.enabled" to "true",
            "mail.host" to "smtp.example.com",
            "mail.provider" to "log",
        )
        assertEquals(MailProvider.LOG, MailSettings.from(config).provider)
    }

    @Test
    fun parsesCloudflareBlockAndTimeout() {
        val config = MapApplicationConfig(
            "mail.cloudflare.endpoint" to "https://mailer.example/send",
            "mail.cloudflare.token" to "secret",
            "mail.cloudflare.timeoutSeconds" to "42",
        )
        val cloudflare = MailSettings.from(config).cloudflare
        assertEquals("https://mailer.example/send", cloudflare.endpoint)
        assertEquals("secret", cloudflare.token)
        assertEquals(42, cloudflare.timeoutSeconds)
        assertTrue(cloudflare.isComplete)
    }

    @Test
    fun invalidCloudflareTimeoutFallsBackToDefault() {
        val config = MapApplicationConfig(
            "mail.cloudflare.timeoutSeconds" to "0",
        )
        assertEquals(10, MailSettings.from(config).cloudflare.timeoutSeconds)
    }

    @Test
    fun keepsExistingSmtpFieldsWorking() {
        val config = MapApplicationConfig(
            "mail.enabled" to "true",
            "mail.host" to "smtp.example.com",
            "mail.port" to "2525",
            "mail.username" to "calendar@example.com",
            "mail.password" to "hunter2",
            "mail.from" to "Kalendee <calendar@example.com>",
            "mail.startTls" to "false",
        )
        val settings = MailSettings.from(config)
        assertEquals(MailProvider.SMTP, settings.provider)
        assertTrue(settings.enabled)
        assertEquals("smtp.example.com", settings.host)
        assertEquals(2525, settings.port)
        assertEquals("calendar@example.com", settings.username)
        assertEquals("hunter2", settings.password)
        assertEquals("Kalendee <calendar@example.com>", settings.from)
        assertFalse(settings.startTls)
    }
}
