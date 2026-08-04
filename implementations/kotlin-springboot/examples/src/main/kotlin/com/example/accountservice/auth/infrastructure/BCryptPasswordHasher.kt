package com.example.accountservice.auth.infrastructure

import com.example.accountservice.auth.application.service.PasswordHasher
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component

private const val STRENGTH = 12

@Component
class BCryptPasswordHasher : PasswordHasher {
    private val encoder = BCryptPasswordEncoder(STRENGTH)

    // Spring Security 7 declares PasswordEncoder.encode's return as @Nullable (JSpecify), but
    // BCryptPasswordEncoder never returns null for a non-null input — assert instead of widening
    // the domain-facing PasswordHasher contract to String?.
    override fun hash(plainPassword: String): String = checkNotNull(encoder.encode(plainPassword))

    override fun verify(
        plainPassword: String,
        passwordHash: String,
    ): Boolean = encoder.matches(plainPassword, passwordHash)
}
