package dev.kolektiv.kalendee.auth

import com.password4j.Argon2Function
import com.password4j.Password
import com.password4j.types.Argon2

interface PasswordHasher {
    fun hash(password: String): String
    fun matches(password: String, encoded: String): Boolean
    fun matchesOrDummy(password: String, encoded: String?): Boolean
}

class Argon2PasswordHasher(settings: AuthSettings) : PasswordHasher {
    private val function: Argon2Function = Argon2Function.getInstance(
        settings.argon2MemoryKib,
        settings.argon2Iterations,
        settings.argon2Parallelism,
        32,
        Argon2.ID,
    )
    private val dummyHash: String = hash("kalendee-dummy-password")

    override fun hash(password: String): String =
        Password.hash(password).addRandomSalt(16).with(function).result

    override fun matches(password: String, encoded: String): Boolean = try {
        Password.check(password, encoded).with(Argon2Function.getInstanceFromHash(encoded))
    } catch (_: IllegalArgumentException) {
        false
    }

    override fun matchesOrDummy(password: String, encoded: String?): Boolean {
        val ok = matches(password, encoded ?: dummyHash)
        return encoded != null && ok
    }
}
