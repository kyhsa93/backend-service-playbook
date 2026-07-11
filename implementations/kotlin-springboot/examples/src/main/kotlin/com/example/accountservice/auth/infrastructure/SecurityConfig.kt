package com.example.accountservice.auth.infrastructure

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
class SecurityConfig(private val jwtAuthenticationFilter: JwtAuthenticationFilter) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize("/auth/sign-in", permitAll)
                authorize("/actuator/health/**", permitAll)
                // STATELESS 세션 + OncePerRequestFilter 조합에서는 요청 처리 중 발생한 예외가
                // 컨테이너의 /error 재전달(forward)로 이어지는데, JwtAuthenticationFilter는
                // 기본적으로 error dispatch에서 재실행되지 않아 SecurityContext가 비어 401/400
                // 대신 403으로 응답이 뒤바뀐다 — /error도 permitAll로 열어 원래 상태 코드가 그대로
                // 전달되게 한다.
                authorize("/error", permitAll)
                authorize(anyRequest, authenticated)
            }
            addFilterBefore<UsernamePasswordAuthenticationFilter>(jwtAuthenticationFilter)
        }
        return http.build()
    }
}
