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
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize("/auth/sign-in", permitAll)
                authorize("/auth/sign-up", permitAll)
                authorize("/actuator/health/**", permitAll)
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
