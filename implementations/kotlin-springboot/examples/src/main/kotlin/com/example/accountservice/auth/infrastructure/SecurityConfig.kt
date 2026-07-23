package com.example.accountservice.auth.infrastructure

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val restAuthenticationEntryPoint: RestAuthenticationEntryPoint,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            // Without this, a missing/invalid bearer token never reaches GlobalExceptionHandler —
            // ExceptionTranslationFilter intercepts it earlier and falls back to Spring Security's
            // default Http403ForbiddenEntryPoint, responding 403 with a generic Spring Boot error body
            // instead of this project's {statusCode, code, message, error} shape
            // (see RestAuthenticationEntryPoint.kt, api-response.md).
            exceptionHandling {
                authenticationEntryPoint = restAuthenticationEntryPoint
            }
            authorizeHttpRequests {
                authorize("/auth/sign-in", permitAll)
                authorize("/auth/sign-up", permitAll)
                authorize("/actuator/health/**", permitAll)
                // A Prometheus scraper hits this endpoint directly (no app-issued JWT) — same
                // rationale as the health probes above (observability.md).
                authorize("/actuator/prometheus", permitAll)
                // The generated OpenAPI document + its UI are documentation, not a protected resource —
                // requiring a bearer token just to view API docs would be self-defeating.
                authorize("/v3/api-docs/**", permitAll)
                authorize("/swagger-ui.html", permitAll)
                authorize("/swagger-ui/**", permitAll)
                // With the STATELESS session + OncePerRequestFilter combination, an exception raised
                // while handling a request leads to the container's /error forward. JwtAuthenticationFilter
                // doesn't re-run on the error dispatch by default, so SecurityContext ends up empty and
                // the response gets swapped from 401/400 to 403 — opening /error to permitAll as well
                // lets the original status code pass through unchanged.
                authorize("/error", permitAll)
                authorize(anyRequest, authenticated)
            }
            addFilterBefore<UsernamePasswordAuthenticationFilter>(jwtAuthenticationFilter)
        }
        return http.build()
    }
}
