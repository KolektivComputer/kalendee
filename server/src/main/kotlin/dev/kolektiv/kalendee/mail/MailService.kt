package dev.kolektiv.kalendee.mail

import dev.kolektiv.kalendee.config.AppSettings
import org.slf4j.LoggerFactory

data class OutboundMail(
    val to: String,
    val subject: String,
    val text: String,
    val html: String? = null,
)

interface Mailer {
    suspend fun send(message: OutboundMail)
}

class MailService(
    private val mailer: Mailer,
    private val app: AppSettings,
) {
    private val log = LoggerFactory.getLogger(MailService::class.java)

    suspend fun sendEmailVerification(to: String, link: String) {
        val subject = "Verify your Kalendee email"
        val text = """
            Welcome to Kalendee.

            Confirm this address to finish setting up your account:
            $link

            If you did not create a Kalendee account, you can ignore this message.
        """.trimIndent()
        val html = """
            <p>Welcome to Kalendee.</p>
            <p>Confirm this address to finish setting up your account:</p>
            <p><a href="$link">Verify email</a></p>
            <p>If you did not create a Kalendee account, you can ignore this message.</p>
        """.trimIndent()
        mailer.send(OutboundMail(to = to, subject = subject, text = text, html = html))
    }

    suspend fun sendNewSignInAlert(to: String, whenText: String, ip: String?, userAgent: String?) {
        val subject = "New sign-in to your Kalendee account"
        val text = """
            A new sign-in to your Kalendee account was detected.

            Time: $whenText
            IP address: ${ip ?: "unknown"}
            Device: ${userAgent ?: "unknown"}

            If this was you, no action is needed. If not, change your password.
        """.trimIndent()
        val html = """
            <p>A new sign-in to your Kalendee account was detected.</p>
            <ul>
              <li><strong>Time:</strong> $whenText</li>
              <li><strong>IP address:</strong> ${ip ?: "unknown"}</li>
              <li><strong>Device:</strong> ${userAgent ?: "unknown"}</li>
            </ul>
            <p>If this was you, no action is needed. If not, change your password.</p>
        """.trimIndent()
        mailer.send(OutboundMail(to = to, subject = subject, text = text, html = html))
    }

    suspend fun sendCalendarShared(
        to: String,
        inviterName: String,
        calendarName: String,
        link: String,
    ) {
        val url = absoluteLink(link)
        val subject = "$inviterName shared a calendar with you"
        val text = """
            $inviterName shared the calendar "$calendarName" with you on Kalendee.

            Open it here:
            $url
        """.trimIndent()
        val html = """
            <p>$inviterName shared the calendar "<strong>$calendarName</strong>" with you on Kalendee.</p>
            <p><a href="$url">Open calendar</a></p>
        """.trimIndent()
        mailer.send(OutboundMail(to = to, subject = subject, text = text, html = html))
    }

    suspend fun sendOrganizationInvitation(
        to: String,
        inviterName: String,
        organizationName: String,
        role: String,
        link: String,
    ) {
        val url = absoluteLink(link)
        val subject = "$inviterName invited you to join $organizationName"
        val text = """
            $inviterName invited you to join "$organizationName" on Kalendee as $role.

            Accept the invitation:
            $url
        """.trimIndent()
        val html = """
            <p>$inviterName invited you to join "<strong>$organizationName</strong>" on Kalendee as <strong>$role</strong>.</p>
            <p><a href="$url">Accept invitation</a></p>
        """.trimIndent()
        mailer.send(OutboundMail(to = to, subject = subject, text = text, html = html))
    }

    suspend fun sendNewFollower(
        to: String,
        followerName: String,
        calendarName: String,
        link: String,
    ) {
        val url = absoluteLink(link)
        val subject = "$followerName is following your calendar"
        val text = """
            $followerName is now following your public calendar "$calendarName" on Kalendee.

            See it here:
            $url
        """.trimIndent()
        val html = """
            <p>$followerName is now following your public calendar "<strong>$calendarName</strong>" on Kalendee.</p>
            <p><a href="$url">Open calendar</a></p>
        """.trimIndent()
        mailer.send(OutboundMail(to = to, subject = subject, text = text, html = html))
    }

    suspend fun sendFollowing(
        to: String,
        calendarName: String,
        ownerName: String,
        link: String,
    ) {
        val url = absoluteLink(link)
        val subject = "You are following $calendarName"
        val text = """
            You are now following "$calendarName" (shared by $ownerName) on Kalendee.

            Open it here:
            $url
        """.trimIndent()
        val html = """
            <p>You are now following "<strong>$calendarName</strong>" (shared by $ownerName) on Kalendee.</p>
            <p><a href="$url">Open calendar</a></p>
        """.trimIndent()
        mailer.send(OutboundMail(to = to, subject = subject, text = text, html = html))
    }

    suspend fun sendFriendRequest(to: String, requesterName: String) {
        val url = absoluteLink("/")
        val subject = "$requesterName wants to be friends"
        val text = """
            $requesterName sent you a friend request on Kalendee.

            Open Kalendee to accept or decline:
            $url
        """.trimIndent()
        val html = """
            <p>$requesterName sent you a friend request on Kalendee.</p>
            <p><a href="$url">Open Kalendee</a></p>
        """.trimIndent()
        mailer.send(OutboundMail(to = to, subject = subject, text = text, html = html))
    }

    suspend fun sendFriendAccepted(to: String, friendName: String) {
        val url = absoluteLink("/")
        val subject = "$friendName accepted your friend request"
        val text = """
            $friendName accepted your friend request on Kalendee.

            Open Kalendee to say hello:
            $url
        """.trimIndent()
        val html = """
            <p>$friendName accepted your friend request on Kalendee.</p>
            <p><a href="$url">Open Kalendee</a></p>
        """.trimIndent()
        mailer.send(OutboundMail(to = to, subject = subject, text = text, html = html))
    }

    suspend fun sendTimeSlotRequest(
        to: String,
        requesterName: String,
        calendarName: String,
        whenText: String,
        message: String?,
    ) {
        val url = absoluteLink("/")
        val subject = "$requesterName requested a time on $calendarName"
        val text = """
            $requesterName requested a time on your calendar "$calendarName".

            Time: $whenText
            ${message?.takeIf { it.isNotBlank() }?.let { "Message: $it\n" }.orEmpty()}
            Review the request:
            $url
        """.trimIndent()
        val html = """
            <p>$requesterName requested a time on your calendar "<strong>$calendarName</strong>".</p>
            <p><strong>Time:</strong> $whenText</p>
            ${message?.takeIf { it.isNotBlank() }?.let { "<p><strong>Message:</strong> $it</p>" }.orEmpty()}
            <p><a href="$url">Review the request</a></p>
        """.trimIndent()
        mailer.send(OutboundMail(to = to, subject = subject, text = text, html = html))
    }

    suspend fun sendEventInvite(
        to: String,
        inviterName: String,
        eventTitle: String,
        whenText: String,
        link: String,
    ) {
        val subject = "$inviterName invited you to $eventTitle"
        val text = """
            $inviterName invited you to "$eventTitle" on Kalendee.

            When: $whenText

            Respond here:
            $link
        """.trimIndent()
        val html = """
            <p>$inviterName invited you to "<strong>$eventTitle</strong>" on Kalendee.</p>
            <p><strong>When:</strong> $whenText</p>
            <p><a href="$link">Open Kalendee</a></p>
        """.trimIndent()
        mailer.send(OutboundMail(to = to, subject = subject, text = text, html = html))
    }

    suspend fun sendEventRsvp(
        to: String,
        attendeeName: String,
        eventTitle: String,
        status: String,
        link: String,
    ) {
        val subject = "$attendeeName responded to $eventTitle"
        val text = """
            $attendeeName responded "$status" to "$eventTitle" on Kalendee.

            Open Kalendee:
            $link
        """.trimIndent()
        val html = """
            <p>$attendeeName responded "<strong>$status</strong>" to "<strong>$eventTitle</strong>" on Kalendee.</p>
            <p><a href="$link">Open Kalendee</a></p>
        """.trimIndent()
        mailer.send(OutboundMail(to = to, subject = subject, text = text, html = html))
    }

    suspend fun sendTimeSlotDecision(
        to: String,
        calendarName: String,
        whenText: String,
        accepted: Boolean,
    ) {
        val url = absoluteLink("/")
        val decision = if (accepted) "accepted" else "declined"
        val subject = "Your time request on $calendarName was $decision"
        val text = """
            Your request for $whenText on "$calendarName" was $decision.

            Open Kalendee:
            $url
        """.trimIndent()
        val html = """
            <p>Your request for $whenText on "<strong>$calendarName</strong>" was $decision.</p>
            <p><a href="$url">Open Kalendee</a></p>
        """.trimIndent()
        mailer.send(OutboundMail(to = to, subject = subject, text = text, html = html))
    }

    fun absoluteLink(path: String): String {
        val base = app.baseUrl.trim().trimEnd('/')
        val relative = if (path.startsWith("/")) path else "/$path"
        if (base.isEmpty()) {
            log.warn("app.baseUrl is blank; using relative link {}", relative)
            return relative
        }
        return base + relative
    }
}
