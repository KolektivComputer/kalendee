package dev.kolektiv.kalendee.oauth

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SealedToken(
    val ciphertext: String,
    val nonce: String,
    val keyVersion: Int,
)

interface TokenVault {
    val currentKeyVersion: Int

    fun seal(value: ByteArray, aad: ByteArray = ByteArray(0)): SealedToken

    fun open(sealed: SealedToken, aad: ByteArray = ByteArray(0)): ByteArray
}

class TokenVaultException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class TokenVaultNotConfiguredException : IllegalStateException(
    "external calendar token encryption is not configured: set KALENDEE_SECRET_KEY " +
        "(base64-encoded 32-byte key) or KALENDEE_SECRET_KEYS",
)

object UnconfiguredTokenVault : TokenVault {
    override val currentKeyVersion: Int = 0

    override fun seal(value: ByteArray, aad: ByteArray): SealedToken = throw TokenVaultNotConfiguredException()

    override fun open(sealed: SealedToken, aad: ByteArray): ByteArray = throw TokenVaultNotConfiguredException()
}

class AesGcmTokenVault(
    private val keys: Map<Int, ByteArray>,
    currentVersion: Int = keys.keys.maxOrNull()
        ?: throw IllegalArgumentException("at least one token key is required"),
) : TokenVault {
    override val currentKeyVersion: Int = currentVersion
    private val random = SecureRandom()

    init {
        require(currentVersion in keys) {
            "token key version $currentVersion is not present in the configured key set"
        }
    }

    override fun seal(value: ByteArray, aad: ByteArray): SealedToken {
        val key = keys.getValue(currentKeyVersion)
        val nonce = ByteArray(NonceBytes).also(random::nextBytes)
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TagBits, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(value)
        return SealedToken(
            ciphertext = encode(ciphertext),
            nonce = encode(nonce),
            keyVersion = currentKeyVersion,
        )
    }

    override fun open(sealed: SealedToken, aad: ByteArray): ByteArray {
        val key = keys[sealed.keyVersion]
            ?: throw TokenVaultException("no token encryption key is configured for version ${sealed.keyVersion}")
        val nonce = decode(sealed.nonce, "token nonce")
        val ciphertext = decode(sealed.ciphertext, "token ciphertext")
        return try {
            val cipher = Cipher.getInstance(Transformation)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TagBits, nonce))
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        } catch (cause: GeneralSecurityException) {
            throw TokenVaultException("token could not be decrypted", cause)
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String, label: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (cause: IllegalArgumentException) {
        throw TokenVaultException("invalid base64 in $label", cause)
    }

    companion object {
        private const val Transformation = "AES/GCM/NoPadding"
        private const val TagBits = 128
        private const val NonceBytes = 12

        fun from(settings: OAuthSettings): TokenVault {
            val keys = linkedMapOf<Int, ByteArray>()
            settings.secretKey
                ?.takeIf { it.isNotBlank() }
                ?.let { keys[1] = decodeKey(it, "KALENDEE_SECRET_KEY") }
            settings.secretKeys
                ?.takeIf { it.isNotBlank() }
                ?.let { keys.putAll(parseSecretKeys(it)) }
            if (keys.isEmpty()) return UnconfiguredTokenVault
            return AesGcmTokenVault(keys)
        }

        private fun parseSecretKeys(raw: String): Map<Int, ByteArray> {
            val element = try {
                Json.parseToJsonElement(raw)
            } catch (cause: SerializationException) {
                throw IllegalArgumentException("KALENDEE_SECRET_KEYS must be a JSON object", cause)
            }
            val obj = element as? JsonObject
                ?: throw IllegalArgumentException("KALENDEE_SECRET_KEYS must be a JSON object")
            return obj.entries.associate { (version, value) ->
                val parsed = version.toIntOrNull()
                    ?: throw IllegalArgumentException("KALENDEE_SECRET_KEYS key '$version' is not a version number")
                parsed to decodeKey(value.jsonPrimitive.content, "KALENDEE_SECRET_KEYS[$version]")
            }
        }

        private fun decodeKey(value: String, source: String): ByteArray {
            val decoded = try {
                Base64.getDecoder().decode(value)
            } catch (cause: IllegalArgumentException) {
                throw IllegalArgumentException("$source must be base64-encoded", cause)
            }
            if (decoded.size !in setOf(16, 24, 32)) {
                throw IllegalArgumentException("$source must decode to an AES key of 16, 24, or 32 bytes")
            }
            return decoded
        }
    }
}
