package dev.kolektiv.kalendee.mail

import org.slf4j.LoggerFactory

class LoggingMailer : Mailer {
    private val log = LoggerFactory.getLogger(LoggingMailer::class.java)

    override suspend fun send(message: OutboundMail) {
        log.info(
            "[mail:dev] to={} subject={}\n{}",
            message.to,
            message.subject,
            message.text,
        )
    }
}
