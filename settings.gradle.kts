rootProject.name = "kalendee"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven("https://repo.kolektiv.computer/repository/maven-releases/")
        // Soft-fork kord-rest (0.18.1-kalendee.2) still lives on Yuri until
        // computer.kolektiv.kord is published to kolektiv maven-releases.
        maven("https://repo.yuri.capital/repository/maven-releases/") {
            mavenContent {
                includeGroupAndSubgroups("dev.kord")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":app:androidApp")
include(":app:desktopApp")
include(":app:shared")
include(":core")
include(":server")
