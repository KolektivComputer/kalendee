package dev.kolektiv.kalendee.auth

import dev.kolektiv.kalendee.mail.MailService
import kotlin.time.Clock

class LoginAlertService(
    private val auth: AuthService,
    private val mail: MailService,
    private val clock: Clock,
) {
    suspend fun onLogin(user: User, ip: String?, userAgent: String?): Boolean {
        val newDevice = auth.recordLoginDevice(user.id, ip, userAgent)
        val email = user.email
        if (newDevice && email != null && user.emailVerified) {
            mail.sendNewSignInAlert(
                to = email,
                whenText = clock.now().toString(),
                ip = ip,
                userAgent = userAgent,
            )
        }
        return newDevice
    }
}
