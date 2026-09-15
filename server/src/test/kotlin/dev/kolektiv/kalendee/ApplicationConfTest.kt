package dev.kolektiv.kalendee

import com.typesafe.config.ConfigFactory
import io.ktor.server.config.HoconApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApplicationConfTest {
    @Test
    fun packagedApplicationConfIsValidHoconWithCoerciblePort() {
        val config = HoconApplicationConfig(ConfigFactory.parseResources("application.conf").resolve())

        val port = config.propertyOrNull("ktor.deployment.port")?.getString()
        assertNotNull(port, "missing ktor.deployment.port")
        assertNotNull(port.toIntOrNull(), "ktor.deployment.port must coerce to an Int but was '$port'")

        val host = config.propertyOrNull("ktor.deployment.host")?.getString()
        assertTrue(!host.isNullOrBlank(), "missing ktor.deployment.host")
    }

    @Test
    fun stringSubstitutionCoercesToIntPort() {
        val config = HoconApplicationConfig(
            ConfigFactory.parseString(
                """
                ktor.deployment.port = 8080
                ktor.deployment.port = ${'$'}{?test.http.port}
                test.http.port = "9090"
                """.trimIndent(),
            ).resolve(),
        )
        assertEquals(9090, config.property("ktor.deployment.port").getString().toInt())
    }

    @Test
    fun packagedApplicationConfExposesNewKeys() {
        val config = HoconApplicationConfig(ConfigFactory.parseResources("application.conf").resolve())
        assertNotNull(config.propertyOrNull("auth.sessionDays"), "missing auth.sessionDays")
        assertNotNull(config.propertyOrNull("auth.cookieName"), "missing auth.cookieName")
        assertNotNull(config.propertyOrNull("mail.provider"), "missing mail.provider")
        assertNotNull(
            config.propertyOrNull("mail.cloudflare.timeoutSeconds")?.getString()?.toIntOrNull(),
            "missing mail.cloudflare.timeoutSeconds",
        )
        assertNotNull(config.propertyOrNull("mail.mailgun.region"), "missing mail.mailgun.region")
        assertNotNull(
            config.propertyOrNull("mail.mailgun.timeoutSeconds")?.getString()?.toIntOrNull(),
            "missing mail.mailgun.timeoutSeconds",
        )
        assertNotNull(config.propertyOrNull("storage.publicBaseUrl"), "missing storage.publicBaseUrl")
    }
}
