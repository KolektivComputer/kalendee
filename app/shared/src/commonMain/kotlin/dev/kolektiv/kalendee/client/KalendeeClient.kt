package dev.kolektiv.kalendee.client

/**
 * Thin HTTP client against `:server` `/api/v1`.
 * Local-only mode never calls the network — the Compose UI keeps events in memory / SessionStore.
 */
class KalendeeClient(
    private val sessions: SessionStore,
    private val http: HttpEngine = HttpEngine(),
) {
    fun session(): Session? = sessions.load()

    fun isLocalOnly(): Boolean = sessions.localOnly() || sessions.load() == null

    suspend fun login(serverBase: String, username: String, password: String): Session {
        val body = http.postJson(
            url = "$serverBase/api/v1/auth/login",
            json = """{"username":${jsonString(username)},"password":${jsonString(password)}}""",
            token = null,
        )
        val user = jsonField(body, "user") ?: throw ClientException("login did not return a user")
        val token = jsonField(body, "token") ?: cookieToken(http.lastSetCookie) ?: throw ClientException("login did not return a session")
        val session = Session(
            token = token,
            username = jsonField(user, "username") ?: username,
            displayName = jsonField(user, "displayName") ?: username,
            serverBase = serverBase.trimEnd('/'),
        )
        sessions.save(session)
        return session
    }

    fun logout() {
        sessions.clear()
    }

    suspend fun events(fromIso: String, toIso: String): String {
        val session = requireSession()
        return http.get(
            url = "${session.serverBase}/api/v1/events?from=$fromIso&to=$toIso",
            token = session.token,
        )
    }

    suspend fun calendars(): String {
        val session = requireSession()
        return http.get(
            url = "${session.serverBase}/api/v1/calendars",
            token = session.token,
        )
    }

    suspend fun listCalendars(): List<ClientCalendar> = parseClientCalendars(calendars())

    suspend fun listEvents(fromIso: String, toIso: String): List<ClientEvent> =
        parseClientEvents(events(fromIso, toIso))

    private fun requireSession(): Session =
        sessions.load() ?: throw ClientException("not signed in")
}

class ClientException(message: String) : RuntimeException(message)

expect class HttpEngine() {
    var lastSetCookie: String?
    suspend fun get(url: String, token: String?): String
    suspend fun postJson(url: String, json: String, token: String?): String
}

internal fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            else -> append(ch)
        }
    }
    append('"')
}

internal fun jsonField(json: String, key: String): String? {
    val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\""
    return Regex(pattern).find(json)?.groupValues?.getOrNull(1)
}

internal fun cookieToken(header: String?): String? {
    if (header.isNullOrBlank()) return null
    val match = Regex("kalendee_session=([^;]+)").find(header) ?: return null
    return match.groupValues[1]
}

internal sealed interface JsValue {
    data class Obj(val map: Map<String, JsValue>) : JsValue
    data class Arr(val items: List<JsValue>) : JsValue
    data class Str(val value: String) : JsValue
    data class Num(val value: String) : JsValue
    data class Bool(val value: Boolean) : JsValue
    data object Null : JsValue
}

internal fun parseClientCalendars(json: String): List<ClientCalendar> =
    jsonRecords(json).mapNotNull { obj ->
        val id = obj.idString("id") ?: return@mapNotNull null
        val name = obj.string("displayName") ?: obj.string("name") ?: obj.string("title") ?: return@mapNotNull null
        ClientCalendar(
            id = id,
            name = name,
            color = obj.string("color") ?: "primary",
            hidden = obj.boolean("hidden") ?: false,
            source = obj.string("source") ?: "local",
        )
    }

internal fun parseClientEvents(json: String): List<ClientEvent> =
    jsonRecords(json).mapNotNull { obj ->
        val id = obj.idString("id") ?: return@mapNotNull null
        val calendarId = obj.idString("calendarId") ?: return@mapNotNull null
        val title = obj.string("title") ?: obj.string("summary") ?: "(untitled)"
        val start = obj.string("start") ?: obj.string("startIso") ?: return@mapNotNull null
        val end = obj.string("end") ?: obj.string("endIso") ?: start
        ClientEvent(
            id = id,
            calendarId = calendarId,
            title = title,
            notes = obj.string("description") ?: obj.string("notes") ?: "",
            location = obj.string("location") ?: "",
            startIso = start,
            endIso = end,
            allDay = obj.boolean("allDay") ?: false,
            etag = obj.string("etag") ?: "",
        )
    }

internal fun jsonRecords(json: String): List<JsValue.Obj> {
    val root = runCatching { JsReader(json).parse() }.getOrNull() ?: return emptyList()
    return when (root) {
        is JsValue.Arr -> root.items.filterIsInstance<JsValue.Obj>()
        is JsValue.Obj -> {
            val items = root.map["items"]
            if (items is JsValue.Arr) items.items.filterIsInstance<JsValue.Obj>() else listOf(root)
        }
        else -> emptyList()
    }
}

private fun JsValue.Obj.string(key: String): String? = map[key]?.asString()

private fun JsValue.Obj.boolean(key: String): Boolean? = when (val value = map[key]) {
    is JsValue.Bool -> value.value
    is JsValue.Str -> value.value.toBooleanStrictOrNull()
    else -> null
}

private fun JsValue.Obj.idString(key: String): String? {
    val value = map[key] ?: return null
    return when (value) {
        is JsValue.Str -> value.value
        is JsValue.Num -> value.value
        is JsValue.Obj -> value.map["value"]?.asString() ?: value.map["id"]?.asString()
        else -> null
    }
}

private fun JsValue.asString(): String? = when (this) {
    is JsValue.Str -> value
    is JsValue.Num -> value
    is JsValue.Bool -> value.toString()
    is JsValue.Obj -> map["value"]?.asString() ?: map["id"]?.asString()
    else -> null
}

private class JsReader(private val source: String) {
    private var index = 0

    fun parse(): JsValue = parseValue()

    private fun parseValue(): JsValue {
        skipWs()
        return when (peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsValue.Str(parseString())
            't' -> {
                expect("true")
                JsValue.Bool(true)
            }
            'f' -> {
                expect("false")
                JsValue.Bool(false)
            }
            'n' -> {
                expect("null")
                JsValue.Null
            }
            else -> parseNumber()
        }
    }

    private fun parseObject(): JsValue.Obj {
        expectChar('{')
        val map = linkedMapOf<String, JsValue>()
        skipWs()
        if (peek() == '}') {
            index++
            return JsValue.Obj(map)
        }
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            expectChar(':')
            map[key] = parseValue()
            skipWs()
            when (peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    break
                }
                else -> error("expected comma or end of object")
            }
        }
        return JsValue.Obj(map)
    }

    private fun parseArray(): JsValue.Arr {
        expectChar('[')
        val items = mutableListOf<JsValue>()
        skipWs()
        if (peek() == ']') {
            index++
            return JsValue.Arr(items)
        }
        while (true) {
            items += parseValue()
            skipWs()
            when (peek()) {
                ',' -> index++
                ']' -> {
                    index++
                    break
                }
                else -> error("expected comma or end of array")
            }
        }
        return JsValue.Arr(items)
    }

    private fun parseString(): String = buildString {
        expectChar('"')
        while (index < source.length) {
            val ch = source[index++]
            when (ch) {
                '"' -> return@buildString
                '\\' -> {
                    val escaped = source.getOrElse(index++) { ' ' }
                    append(
                        when (escaped) {
                            '"' -> '"'
                            '\\' -> '\\'
                            '/' -> '/'
                            'b' -> '\b'
                            'f' -> '\u000C'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                val hex = source.substring(index, (index + 4).coerceAtMost(source.length))
                                index += hex.length
                                hex.toIntOrNull(16)?.toChar() ?: '?'
                            }
                            else -> escaped
                        },
                    )
                }
                else -> append(ch)
            }
        }
    }

    private fun parseNumber(): JsValue.Num {
        skipWs()
        val start = index
        if (peek() == '-') index++
        while (index < source.length && source[index] in '0'..'9') index++
        if (peek() == '.') {
            index++
            while (index < source.length && source[index] in '0'..'9') index++
        }
        if (peek() == 'e' || peek() == 'E') {
            index++
            if (peek() == '+' || peek() == '-') index++
            while (index < source.length && source[index] in '0'..'9') index++
        }
        return JsValue.Num(source.substring(start, index))
    }

    private fun expect(token: String) {
        skipWs()
        if (!source.startsWith(token, index)) error("expected $token")
        index += token.length
    }

    private fun expectChar(ch: Char) {
        skipWs()
        if (peek() != ch) error("expected $ch")
        index++
    }

    private fun skipWs() {
        while (index < source.length && source[index].isWhitespace()) index++
    }

    private fun peek(): Char = source.getOrElse(index) { '\u0000' }
}
