package dev.kolektiv.kalendee.auth

enum class EmailVerificationPolicy {
    Optional,
    Soft,
    Required,
    ;

    val wire: String
        get() = name.lowercase()

    companion object {
        fun parse(raw: String): EmailVerificationPolicy = when (raw.trim().lowercase()) {
            "optional" -> Optional
            "soft" -> Soft
            "required" -> Required
            else -> error("unknown auth.emailVerification: $raw")
        }
    }
}
