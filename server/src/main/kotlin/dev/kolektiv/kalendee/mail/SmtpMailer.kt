package dev.kolektiv.kalendee.mail

import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmtpMailer(private val settings: MailSettings) : Mailer {
    override suspend fun send(message: OutboundMail) {
        withContext(Dispatchers.IO) {
            val session = Session.getInstance(properties(), authenticator(settings))
            val mime = MimeMessage(session)
            mime.setFrom(InternetAddress.parse(settings.from).first())
            mime.setRecipients(Message.RecipientType.TO, InternetAddress.parse(message.to))
            mime.setSubject(message.subject, "UTF-8")
            if (message.html == null) {
                mime.setText(message.text, "UTF-8")
            } else {
                mime.setContent(alternative(message.text, message.html))
            }
            Transport.send(mime)
        }
    }

    private fun properties(): Properties = Properties().apply {
        this["mail.smtp.host"] = settings.host
        this["mail.smtp.port"] = settings.port.toString()
        this["mail.smtp.auth"] = settings.username.isNotBlank().toString()
        this["mail.smtp.starttls.enable"] = settings.startTls.toString()
    }

    private fun authenticator(settings: MailSettings): Authenticator? {
        if (settings.username.isBlank()) return null
        return object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication =
                PasswordAuthentication(settings.username, settings.password.orEmpty())
        }
    }

    private fun alternative(text: String, html: String): MimeMultipart =
        MimeMultipart("alternative").apply {
            addBodyPart(bodyPart(text, "text/plain"))
            addBodyPart(bodyPart(html, "text/html"))
        }

    private fun bodyPart(content: String, contentType: String): MimeBodyPart =
        MimeBodyPart().apply {
            setText(content, "UTF-8", contentType.substringAfter('/'))
        }
}
