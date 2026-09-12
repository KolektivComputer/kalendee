package dev.kolektiv.kalendee.oauth

import io.ktor.server.config.ApplicationConfig

data class ProviderOAuthSettings(
    val clientId: String,
    val clientSecret: String,
) {
    val enabled: Boolean get() = clientId.isNotBlank()
}

data class OAuthSettings(
    val google: ProviderOAuthSettings,
    val microsoft: ProviderOAuthSettings,
    val secretKey: String? = null,
    val secretKeys: String? = null,
) {
    companion object {
        fun from(config: ApplicationConfig): OAuthSettings = OAuthSettings(
            google = provider(config, "oauth.google"),
            microsoft = provider(config, "oauth.microsoft"),
            secretKey = config.propertyOrNull("oauth.secretKey")?.getString()?.takeIf { it.isNotBlank() },
            secretKeys = config.propertyOrNull("oauth.secretKeys")?.getString()?.takeIf { it.isNotBlank() },
        )

        private fun provider(config: ApplicationConfig, prefix: String): ProviderOAuthSettings = ProviderOAuthSettings(
            clientId = config.propertyOrNull("$prefix.clientId")?.getString().orEmpty().trim(),
            clientSecret = config.propertyOrNull("$prefix.clientSecret")?.getString().orEmpty().trim(),
        )
    }
}
