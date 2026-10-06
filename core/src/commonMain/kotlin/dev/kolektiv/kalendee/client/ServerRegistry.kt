package dev.kolektiv.kalendee.client

import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
)

@Serializable
data class ServerAccount(
    val profile: ServerProfile,
    val username: String? = null,
    val token: String? = null,
)

class ServerRegistry(private val store: KeyValueStore) {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    fun accounts(): List<ServerAccount> {
        val raw = store.getString(StorageKey) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ServerAccount>>(raw) }.getOrDefault(emptyList())
    }

    fun account(id: String): ServerAccount? = accounts().firstOrNull { it.profile.id == id }

    fun upsert(account: ServerAccount) {
        val current = accounts()
        val index = current.indexOfFirst { it.profile.id == account.profile.id }
        val updated = if (index >= 0) {
            current.toMutableList().apply { this[index] = account }
        } else {
            current + account
        }
        persist(updated)
    }

    fun remove(id: String) {
        persist(accounts().filterNot { it.profile.id == id })
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val account = account(id) ?: return
        upsert(account.copy(profile = account.profile.copy(enabled = enabled)))
    }

    fun setSession(id: String, username: String?, token: String?) {
        val account = account(id) ?: return
        upsert(account.copy(username = username, token = token))
    }

    private fun persist(accounts: List<ServerAccount>) {
        store.putString(StorageKey, json.encodeToString(accounts))
    }

    private companion object {
        const val StorageKey = "kalendee.servers.v1"
    }
}

fun randomId(): String = buildString(24) {
    repeat(24) {
        append(HexDigits[Random.nextInt(HexDigits.length)])
    }
}

fun normalizeBaseUrl(raw: String): String {
    val trimmed = raw.trim()
    require(trimmed.isNotEmpty()) { "base url must not be blank" }
    val withScheme = if (
        trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        trimmed
    } else {
        "https://$trimmed"
    }
    return withScheme.trimEnd('/')
}

private const val HexDigits = "0123456789abcdef"
