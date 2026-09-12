package dev.kolektiv.kalendee.env

import java.nio.file.Files
import java.nio.file.Path

object LocalEnv {
    fun get(name: String): String? = System.getenv(name) ?: file[name]

    internal val file: Map<String, String> by lazy { load() }

    internal fun parseLine(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val body = trimmed.removePrefix("export ").trim()
        val eq = body.indexOf('=')
        if (eq <= 0) return null
        val key = body.take(eq).trim()
        if (key.isEmpty()) return null
        var value = body.substring(eq + 1).trim()
        if (value.length >= 2) {
            val quote = value.first()
            if ((quote == '"' || quote == '\'') && value.last() == quote) {
                value = value.substring(1, value.length - 1)
            }
        }
        return key to value
    }

    private fun load(): Map<String, String> {
        val cwd = Path.of(System.getProperty("user.dir", "."))
        val path = cwd.resolve(".env.local")
        if (!Files.isRegularFile(path)) return emptyMap()
        val out = linkedMapOf<String, String>()
        Files.readAllLines(path).forEach { line ->
            val parsed = parseLine(line) ?: return@forEach
            out.putIfAbsent(parsed.first, parsed.second)
        }
        return out
    }
}
