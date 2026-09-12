package dev.kolektiv.kalendee

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform