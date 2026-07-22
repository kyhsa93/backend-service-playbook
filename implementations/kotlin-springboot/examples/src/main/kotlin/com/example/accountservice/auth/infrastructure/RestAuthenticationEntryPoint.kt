package com.example.accountservice.auth.infrastructure

import com.example.accountservice.account.interfaces.rest.ErrorResponse
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component

/**
 * Without this, an unauthenticated request to a protected endpoint (no `Authorization` header, or a
 * malformed/invalid/expired bearer token) never reaches [com.example.accountservice.common.GlobalExceptionHandler]
 * at all — `ExceptionTranslationFilter` intercepts it earlier in the Filter chain and falls back to
 * Spring Security's default `Http403ForbiddenEntryPoint`, which responds `403 Forbidden` with a generic
 * Spring Boot error body (`{timestamp, status, error, path}`) instead of this project's
 * `{statusCode, code, message, error}` shape — a mismatch only an actual unauthenticated HTTP request
 * would reveal (annotations/tests alone don't catch it, see api-response.md).
 *
 * Registering this bean as `exceptionHandling { authenticationEntryPoint = ... }` in [SecurityConfig]
 * makes every authentication failure respond `401` with the same [ErrorResponse] shape every other
 * error uses, matching what `@ApiResponse(responseCode = "401", ...)` documents on every authenticated
 * Controller.
 */
@Component
class RestAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        val body =
            ErrorResponse(
                statusCode = HttpStatus.UNAUTHORIZED.value(),
                code = "UNAUTHORIZED",
                message = "The bearer token is missing, malformed, or invalid.",
                error = HttpStatus.UNAUTHORIZED.reasonPhrase,
            )
        response.writer.write(objectMapper.writeValueAsString(body))
    }
}
