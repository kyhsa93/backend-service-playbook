package com.example.accountservice.auth.application.command

import com.example.accountservice.auth.application.AuthService
import com.example.accountservice.auth.application.query.CredentialQuery
import com.example.accountservice.auth.application.service.PasswordHasher
import com.example.accountservice.auth.domain.InvalidCredentialsException
import org.springframework.stereotype.Service

@Service
class SignInService(
    private val credentialQuery: CredentialQuery,
    private val passwordHasher: PasswordHasher,
    private val authService: AuthService,
) {

    fun signIn(command: SignInCommand): String {
        // 아이디 미존재/비밀번호 불일치를 동일한 예외로 응답 — 존재하는 아이디를 추측 가능하게 만들지 않기 위함.
        val credential = credentialQuery.findByUserId(command.userId) ?: throw InvalidCredentialsException()
        if (!passwordHasher.verify(command.password, credential.passwordHash)) throw InvalidCredentialsException()

        return authService.sign(credential.userId)
    }
}
