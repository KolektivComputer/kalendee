package dev.kolektiv.kalendee.client

actual class HttpEngine actual constructor() {
    actual var lastSetCookie: String? = null

    actual suspend fun get(url: String, token: String?): String {
        throw ClientException("iOS HTTP client is not wired yet")
    }

    actual suspend fun postJson(url: String, json: String, token: String?): String {
        throw ClientException("iOS HTTP client is not wired yet")
    }
}
