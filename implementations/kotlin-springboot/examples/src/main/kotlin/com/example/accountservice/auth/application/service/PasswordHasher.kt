package com.example.accountservice.auth.application.service

/**
 * 비밀번호 해싱/검증 Technical Service — 인터페이스는 Application 레이어(사용하는 쪽이 필요로 하는
 * 형태)에 두고, 구현체(BCrypt)는 infrastructure/에 둔다(domain-service.md의 Technical Service 패턴,
 * secret/application/service/SecretService.kt와 동일한 구조).
 */
interface PasswordHasher {
    fun hash(plainPassword: String): String
    fun verify(plainPassword: String, passwordHash: String): Boolean
}
