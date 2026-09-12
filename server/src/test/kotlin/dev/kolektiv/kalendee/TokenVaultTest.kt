package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.oauth.AesGcmTokenVault
import dev.kolektiv.kalendee.oauth.OAuthSettings
import dev.kolektiv.kalendee.oauth.ProviderOAuthSettings
import dev.kolektiv.kalendee.oauth.TokenVaultException
import dev.kolektiv.kalendee.oauth.TokenVaultNotConfiguredException
import dev.kolektiv.kalendee.oauth.UnconfiguredTokenVault
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TokenVaultTest {
    private val keyA = ByteArray(32) { (it + 1).toByte() }
    private val keyB = ByteArray(32) { (it + 100).toByte() }
    private val plaintext = "refresh-token-value".toByteArray(Charsets.UTF_8)

    @Test
    fun roundTripsPlaintextWithFreshNonces() {
        val vault = AesGcmTokenVault(mapOf(1 to keyA))
        val first = vault.seal(plaintext)
        val second = vault.seal(plaintext)
        assertEquals(1, first.keyVersion)
        assertContentEquals(plaintext, vault.open(first))
        assertContentEquals(plaintext, vault.open(second))
        assertTrue(first.nonce != second.nonce, "each seal must use a fresh nonce")
        assertTrue(first.ciphertext != second.ciphertext, "each seal must produce fresh ciphertext")
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val vault = AesGcmTokenVault(mapOf(1 to keyA))
        val sealed = vault.seal(plaintext)
        val bytes = Base64.getDecoder().decode(sealed.ciphertext)
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        val tampered = sealed.copy(ciphertext = Base64.getEncoder().encodeToString(bytes))
        assertFailsWith<TokenVaultException> { vault.open(tampered) }
    }

    @Test
    fun unknownKeyVersionIsRejected() {
        val vault = AesGcmTokenVault(mapOf(1 to keyA))
        val sealed = vault.seal(plaintext)
        assertFailsWith<TokenVaultException> { vault.open(sealed.copy(keyVersion = 99)) }
    }

    @Test
    fun aadMismatchIsRejected() {
        val vault = AesGcmTokenVault(mapOf(1 to keyA))
        val sealed = vault.seal(plaintext, aad = "connection-a".toByteArray(Charsets.UTF_8))
        assertFailsWith<TokenVaultException> {
            vault.open(sealed, aad = "connection-b".toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun rotatedKeysReopenOldTokensAndSealWithCurrentVersion() {
        val old = AesGcmTokenVault(mapOf(1 to keyA))
        val sealed = old.seal(plaintext)
        val rotated = AesGcmTokenVault(mapOf(1 to keyA, 2 to keyB))
        assertEquals(2, rotated.currentKeyVersion)
        assertContentEquals(plaintext, rotated.open(sealed))
        val fresh = rotated.seal(plaintext)
        assertEquals(2, fresh.keyVersion)
        assertFailsWith<TokenVaultException> { old.open(fresh) }
    }

    @Test
    fun emptyKeySetIsRejected() {
        assertFailsWith<IllegalArgumentException> { AesGcmTokenVault(emptyMap()) }
    }

    @Test
    fun missingConfigurationFailsClosed() {
        val settings = OAuthSettings(
            discord = ProviderOAuthSettings(clientId = "did", clientSecret = "dsecret"),
        )
        val vault = AesGcmTokenVault.from(settings)
        assertSame(UnconfiguredTokenVault, vault)
        assertFailsWith<TokenVaultNotConfiguredException> { vault.seal(plaintext) }
        assertFailsWith<TokenVaultNotConfiguredException> {
            vault.open(
                dev.kolektiv.kalendee.oauth.SealedToken(
                    ciphertext = "AA==",
                    nonce = "AA==",
                    keyVersion = 1,
                ),
            )
        }
    }

    @Test
    fun parsesSingleSecretKeyAsVersionOne() {
        val vault = AesGcmTokenVault.from(settings(secretKey = base64(keyA)))
        assertEquals(1, vault.currentKeyVersion)
        assertContentEquals(plaintext, vault.open(vault.seal(plaintext)))
    }

    @Test
    fun parsesSecretKeysJsonAndUsesHighestVersion() {
        val vault = AesGcmTokenVault.from(
            settings(secretKeys = """{"1":"${base64(keyA)}","2":"${base64(keyB)}"}"""),
        )
        assertEquals(2, vault.currentKeyVersion)
        val sealed = vault.seal(plaintext)
        assertEquals(2, sealed.keyVersion)
        assertContentEquals(plaintext, vault.open(sealed))
    }

    @Test
    fun rejectsMalformedSecretKeys() {
        assertFailsWith<IllegalArgumentException> {
            AesGcmTokenVault.from(settings(secretKey = "not-base64!"))
        }
        assertFailsWith<IllegalArgumentException> {
            AesGcmTokenVault.from(settings(secretKeys = "[]"))
        }
    }

    private fun settings(secretKey: String? = null, secretKeys: String? = null) = OAuthSettings(
        discord = ProviderOAuthSettings(clientId = "did", clientSecret = "dsecret"),
        secretKey = secretKey,
        secretKeys = secretKeys,
    )

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
