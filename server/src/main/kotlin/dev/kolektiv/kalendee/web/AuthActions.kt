package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.EmailVerificationService
import dev.kolektiv.kalendee.auth.LoginAlertService
import dev.kolektiv.kalendee.auth.LoginUser
import dev.kolektiv.kalendee.auth.PublicAccessMode
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.auth.UpdateUser
import dev.kolektiv.kalendee.auth.clearSessionCookie
import dev.kolektiv.kalendee.auth.sessionToken
import dev.kolektiv.kalendee.auth.setSessionCookie
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.plugins.clientIp
import dev.kolektiv.kalendee.plugins.clientUserAgent
import dev.kolektiv.keel.KeelAction
import dev.kolektiv.keel.ktor.ActionRequest
import dev.kolektiv.keel.ktor.PageValidationException

class AuthActions(
    private val auth: AuthService,
    private val settings: AuthSettings,
    private val verification: EmailVerificationService,
    private val loginAlerts: LoginAlertService,
) {
    @KeelAction("kalendee.login")
    suspend fun login(input: LoginIn): LoginOut = mapAuthErrors("password") {
        val result = auth.login(LoginUser(username = input.username, password = input.password))
        val session = result.session
        if (session != null) {
            val call = ActionRequest.current().call
            call.setSessionCookie(session.token, settings)
            loginAlerts.onLogin(session.user, call.clientIp(), call.clientUserAgent())
        }
        LoginOut(
            viewer = session?.user?.toViewer(),
            verificationRequired = result.verificationRequired,
            email = result.email,
        )
    }

    @KeelAction("kalendee.register")
    suspend fun register(input: RegisterIn): RegisterOut = mapAuthErrors("username") {
        val result = auth.register(
            RegisterUser(username = input.username, password = input.password, email = input.email),
        )
        val session = result.session
        if (session != null) {
            ActionRequest.current().call.setSessionCookie(session.token, settings)
        }
        RegisterOut(
            viewer = session?.user?.toViewer(),
            verificationRequired = result.verificationRequired,
            email = result.email,
        )
    }

    @KeelAction("kalendee.updateSettings")
    suspend fun updateSettings(input: UpdateSettingsIn): Viewer = mapDomainErrors("displayName") {
        val user = requireSessionUser(auth, settings)
        auth.updateUser(
            user.id,
            UpdateUser(
                displayName = input.displayName,
                timeZone = input.timeZone,
                accent = input.accent,
                email = input.email,
                clearEmail = input.clearEmail,
            ),
        )?.toViewer() ?: throw CalendarException.NotFound("user not found")
    }

    @KeelAction("kalendee.setUserPublicAccess")
    suspend fun setUserPublicAccess(input: SetUserPublicAccessIn): Viewer = mapDomainErrors("mode") {
        val user = requireSessionUser(auth, settings)
        val mode = PublicAccessMode.parse(input.mode)
        auth.setUserPublicAccess(user.id, mode)?.toViewer()
            ?: throw CalendarException.NotFound("user not found")
    }

    @KeelAction("kalendee.verifyEmail")
    suspend fun verifyEmail(input: VerifyEmailIn): VerifyEmailOut = mapDomainErrors("token") {
        val user = verification.verify(input.token)
        VerifyEmailOut(ok = user != null, email = user?.email)
    }

    @KeelAction("kalendee.resendVerification")
    suspend fun resendVerification(input: ResendVerificationIn): ResendVerificationOut {
        verification.resendFor(input.username)
        return ResendVerificationOut(ok = true)
    }

    @KeelAction("kalendee.logout")
    suspend fun logout(input: LogoutIn): LogoutOut {
        val call = ActionRequest.current().call
        auth.logout(call.sessionToken(settings))
        call.clearSessionCookie(settings)
        return LogoutOut()
    }
}

private suspend fun <T> mapAuthErrors(field: String, block: suspend () -> T): T = try {
    block()
} catch (cause: CalendarException.Unauthorized) {
    throw PageValidationException(
        mapOf(field to listOf(cause.message ?: "invalid username or password")),
        Unit,
    )
} catch (cause: CalendarException.Invalid) {
    throw PageValidationException(
        mapOf(field to listOf(cause.message ?: "invalid")),
        Unit,
    )
} catch (cause: CalendarException.Conflict) {
    val conflictField = if (cause.message?.startsWith("email") == true) "email" else "username"
    throw PageValidationException(
        mapOf(conflictField to listOf(cause.message ?: "username taken")),
        Unit,
    )
} catch (cause: CalendarException.Forbidden) {
    throw PageValidationException(
        mapOf("username" to listOf(cause.message ?: "registration is closed")),
        Unit,
    )
} catch (cause: CalendarException.TooManyRequests) {
    throw PageValidationException(
        mapOf(field to listOf(cause.message ?: "too many requests")),
        Unit,
    )
}
