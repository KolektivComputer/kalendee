package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.EmailVerificationService
import dev.kolektiv.kalendee.auth.LoginAlertService
import dev.kolektiv.kalendee.auth.LoginUser
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.auth.UpdateUser
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.auth.clearSessionCookie
import dev.kolektiv.kalendee.auth.sessionToken
import dev.kolektiv.kalendee.auth.setSessionCookie
import dev.kolektiv.kalendee.plugins.clientIp
import dev.kolektiv.kalendee.plugins.clientUserAgent
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post

fun Route.authPublicRoutes(
    authService: AuthService,
    settings: AuthSettings,
    verification: EmailVerificationService,
    loginAlerts: LoginAlertService,
) {
    post("/auth/register") {
        val result = authService.register(call.receive<RegisterUser>())
        val session = result.session
        if (session != null) {
            call.setSessionCookie(session.token, settings)
        }
        call.respond(
            HttpStatusCode.Created,
            AuthResult(user = session?.user, verificationRequired = result.verificationRequired, email = result.email),
        )
    }
    post("/auth/login") {
        val result = authService.login(call.receive<LoginUser>())
        val session = result.session
        if (session != null) {
            call.setSessionCookie(session.token, settings)
            loginAlerts.onLogin(session.user, call.clientIp(), call.clientUserAgent())
        }
        call.respond(
            AuthResult(user = session?.user, verificationRequired = result.verificationRequired, email = result.email),
        )
    }
    post("/auth/verify-email") {
        val body = call.receive<VerifyEmailBody>()
        val user = verification.verify(body.token)
        call.respond(VerifyEmailResponse(ok = user != null, email = user?.email))
    }
    post("/auth/resend-verification") {
        val body = call.receive<ResendVerificationBody>()
        verification.resendFor(body.username)
        call.respond(OkResponse())
    }
}

fun Route.authSessionRoutes(authService: AuthService, settings: AuthSettings) {
    get("/auth/me") {
        call.respond(call.user())
    }
    patch("/auth/me") {
        val updated = authService.updateUser(call.user().id, call.receive<UpdateUser>())
            ?: throw CalendarException.NotFound("user not found")
        call.respond(updated)
    }
    post("/auth/logout") {
        authService.logout(call.sessionToken(settings))
        call.clearSessionCookie(settings)
        call.respond(HttpStatusCode.NoContent)
    }
}
