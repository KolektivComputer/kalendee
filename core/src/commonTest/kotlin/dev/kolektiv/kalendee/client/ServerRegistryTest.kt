package dev.kolektiv.kalendee.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerRegistryTest {
    @Test
    fun persistsAccountsAcrossRegistryInstances() {
        val store = FakeKeyValueStore()
        val account = ServerAccount(
            profile = ServerProfile(id = "s1", name = "Home", baseUrl = "https://home.example"),
            username = "alice",
            token = "kalendee_session=tok",
        )

        ServerRegistry(store).upsert(account)

        val reloaded = ServerRegistry(store)
        assertEquals(listOf(account), reloaded.accounts())
        assertEquals(account, reloaded.account("s1"))
    }

    @Test
    fun upsertReplacesExistingAccountInPlace() {
        val registry = ServerRegistry(FakeKeyValueStore())
        registry.upsert(ServerAccount(ServerProfile(id = "s1", name = "One", baseUrl = "https://one.example")))
        registry.upsert(ServerAccount(ServerProfile(id = "s2", name = "Two", baseUrl = "https://two.example")))
        registry.upsert(ServerAccount(ServerProfile(id = "s1", name = "One renamed", baseUrl = "https://one.example")))

        assertEquals(listOf("One renamed", "Two"), registry.accounts().map { it.profile.name })
    }

    @Test
    fun removeDropsAccountAndLeavesOthers() {
        val store = FakeKeyValueStore()
        val registry = ServerRegistry(store)
        registry.upsert(ServerAccount(ServerProfile(id = "s1", name = "One", baseUrl = "https://one.example")))
        registry.upsert(ServerAccount(ServerProfile(id = "s2", name = "Two", baseUrl = "https://two.example")))

        registry.remove("s1")

        assertNull(registry.account("s1"))
        assertEquals(listOf("s2"), ServerRegistry(store).accounts().map { it.profile.id })
    }

    @Test
    fun setEnabledAndSetSessionPersist() {
        val store = FakeKeyValueStore()
        val registry = ServerRegistry(store)
        registry.upsert(ServerAccount(ServerProfile(id = "s1", name = "Home", baseUrl = "https://home.example")))

        registry.setEnabled("s1", false)
        registry.setSession("s1", "alice", "kalendee_session=tok")

        val reloaded = ServerRegistry(store).account("s1")!!
        assertEquals("s1", reloaded.profile.id)
        assertFalse(reloaded.profile.enabled)
        assertEquals("alice", reloaded.username)
        assertEquals("kalendee_session=tok", reloaded.token)
    }

    @Test
    fun unknownAccountMutationsAreNoOps() {
        val registry = ServerRegistry(FakeKeyValueStore())
        registry.setEnabled("missing", false)
        registry.setSession("missing", "alice", "tok")
        assertTrue(registry.accounts().isEmpty())
    }

    @Test
    fun corruptPayloadReadsAsEmpty() {
        val store = FakeKeyValueStore()
        store.putString("kalendee.servers.v1", "not json")
        assertTrue(ServerRegistry(store).accounts().isEmpty())
    }

    @Test
    fun randomIdIsHex() {
        val first = randomId()
        assertEquals(24, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
        assertTrue(first != randomId())
    }

    @Test
    fun normalizeBaseUrlAddsSchemeAndTrimsSlashes() {
        assertEquals("https://calendar.example", normalizeBaseUrl("calendar.example"))
        assertEquals("https://calendar.example", normalizeBaseUrl("  https://calendar.example/  "))
        assertEquals("http://calendar.example", normalizeBaseUrl("http://calendar.example///"))
        assertEquals("https://calendar.example/api", normalizeBaseUrl("calendar.example/api/"))
    }

    @Test
    fun normalizeBaseUrlRejectsBlank() {
        assertFailsWith<IllegalArgumentException> { normalizeBaseUrl("   ") }
    }
}
