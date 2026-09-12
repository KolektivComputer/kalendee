package dev.kolektiv.kalendee.di

import dev.kolektiv.keel.bundle.FrontendBundle
import io.ktor.server.application.ApplicationEnvironment
import java.nio.file.Files
import java.nio.file.Path

fun loadFrontendBundle(environment: ApplicationEnvironment): FrontendBundle {
    val override = runCatching {
        environment.config.propertyOrNull("keel.packDir")?.getString()?.trim()
    }.getOrNull().orEmpty()
    if (override.isNotEmpty()) {
        val path = Path.of(override)
        return if (Files.isDirectory(path)) {
            FrontendBundle.fromDirectory(path)
        } else {
            FrontendBundle.fromFile(path)
        }
    }
    return FrontendBundle.fromResource("keel/kalendee.feb")
}
