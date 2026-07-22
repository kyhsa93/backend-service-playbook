package com.example.accountservice.auth.application.service

/**
 * The password hashing/verification Technical Service — the interface lives in the Application layer
 * (in the shape the caller needs), and the implementation (BCrypt) lives in infrastructure/
 * (the Technical Service pattern from domain-service.md, the same structure as
 * secret/application/service/SecretService.kt).
 */
interface PasswordHasher {
    fun hash(plainPassword: String): String

    fun verify(
        plainPassword: String,
        passwordHash: String,
    ): Boolean
}
