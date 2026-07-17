package com.example.accountservice.auth.infrastructure

import com.example.accountservice.auth.application.service.PasswordHasher
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component

private const val STRENGTH = 12

@Component
class BCryptPasswordHasher : PasswordHasher {
    private val encoder = BCryptPasswordEncoder(STRENGTH)

    override fun hash(plainPassword: String): String = encoder.encode(plainPassword)

    override fun verify(
        plainPassword: String,
        passwordHash: String,
    ): Boolean = encoder.matches(plainPassword, passwordHash)
}
