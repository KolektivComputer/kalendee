package dev.kolektiv.kalendee.client

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URI

actual class HttpEngine actual constructor() {
    actual var lastSetCookie: String? = null

    actual suspend fun get(url: String, token: String?): String = request("GET", url, null, token)

    actual suspend fun postJson(url: String, json: String, token: String?): String =
        request("POST", url, json, token)

    private fun request(method: String, url: String, json: String?, token: String?): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        if (token != null) connection.setRequestProperty("Cookie", "kalendee_session=$token")
        if (json != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(json.toByteArray()) }
        }
        lastSetCookie = connection.getHeaderField("Set-Cookie")
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        if (connection.responseCode !in 200..299) {
            throw ClientException("HTTP ${connection.responseCode}: $body")
        }
        return body
    }
}
