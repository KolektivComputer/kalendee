package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.plugins.configureKoin
import io.ktor.server.application.Application
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.testing.TestEngine
import io.ktor.server.testing.createTestEnvironment
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Clock
import org.koin.ktor.ext.get

class KoinReloadTest {
    @Test
    fun replacementApplicationKeepsKoinAfterPreviousStops() {
        val pack = Files.createTempDirectory("kalendee-keel-pack")
        writeStubPack(pack)
        val environment = createTestEnvironment {
            config = postgresTestConfig(packDir = pack.toString())
        }
        val applications = mutableListOf<Application>()
        val server = EmbeddedServer(
            serverConfig(environment) {
                developmentMode = true
                watchPaths = emptyList()
                module {
                    configureKoin()
                    applications += this
                }
            },
            TestEngine,
            {},
        )

        server.start()
        try {
            server.reload()
            assertEquals(2, applications.size)
            assertSame(Clock.System, applications.last().get<Clock>())
        } finally {
            server.stop()
        }
    }
}
