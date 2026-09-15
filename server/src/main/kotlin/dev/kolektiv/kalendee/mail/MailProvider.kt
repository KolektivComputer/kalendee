package dev.kolektiv.kalendee.mail

import org.slf4j.LoggerFactory

/**
 * Transport used by [Mailer].
 *
 * [parse] is lenient: parsing is case-insensitive and whitespace-trimmed, and
 * an unrecognized value logs a warning and falls back to [LOG] rather than
 * crashing startup.
 */
enum class MailProvider {
    LOG,
    SMTP,
    CLOUDFLARE,
    MAILGUN,
    ;

    companion object {
        private val log = LoggerFactory.getLogger(MailProvider::class.java)

        fun parse(raw: String): MailProvider = when (raw.trim().lowercase()) {
            "log" -> LOG
            "smtp" -> SMTP
            "cloudflare" -> CLOUDFLARE
            "mailgun" -> MAILGUN
            else -> {
                log.warn("unknown mail.provider '{}': falling back to 'log'", raw)
                LOG
            }
        }
    }
}
