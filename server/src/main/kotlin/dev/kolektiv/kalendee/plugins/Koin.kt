package dev.kolektiv.kalendee.plugins

import dev.kolektiv.kalendee.di.serverModule
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.ktor.plugin.setKoin
import org.koin.logger.slf4jLogger

fun Application.configureKoin(extra: Module? = null) {
    val development = developmentMode
    val koinApplication = koinApplication {
        slf4jLogger()
        modules(serverModule(environment, development))
        extra?.let { modules(it) }
    }
    // Keep Koin scoped to this Application. The koin-ktor plugin stores its Koin in the
    // global context and closes it on every ApplicationStopping event; during a Ktor
    // dev-mode reload the replacement application is built before the previous one is
    // stopped, so the plugin would close the replacement's Koin and leave it serving
    // requests with a closed root scope.
    setKoin(koinApplication.koin)
    monitor.subscribe(ApplicationStopping) { stopped ->
        if (stopped === this) koinApplication.koin.close()
    }
}
