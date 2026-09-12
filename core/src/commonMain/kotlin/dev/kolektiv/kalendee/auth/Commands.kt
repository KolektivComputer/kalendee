package dev.kolektiv.kalendee.auth

import kotlinx.serialization.Serializable

@Serializable
data class RegisterUser(
    val username: String,
    val password: String,
    val email: String? = null,
)

@Serializable
data class LoginUser(
    val username: String,
    val password: String,
)

@Serializable
data class UpdateUser(
    val displayName: String? = null,
    val timeZone: String? = null,
    val accent: String? = null,
    val email: String? = null,
    val clearEmail: Boolean = false,
)
