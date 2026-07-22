package com.example.accountservice.auth.application.command

import com.example.accountservice.auth.application.AuthService
import com.example.accountservice.auth.application.query.CredentialQuery
import com.example.accountservice.auth.application.service.PasswordHasher
import com.example.accountservice.auth.domain.CredentialFindQuery
import com.example.accountservice.auth.domain.InvalidCredentialsException
import org.springframework.stereotype.Service

@Service
class SignInService(
    private val credentialQuery: CredentialQuery,
    private val passwordHasher: PasswordHasher,
    private val authService: AuthService,
) {
    fun signIn(command: SignInCommand): String {
        // Responds with the same exception whether the user ID doesn't exist or the password doesn't
        // match — this prevents an attacker from being able to infer which user IDs exist.
        val credential =
            credentialQuery
                .findCredentials(CredentialFindQuery(page = 0, take = 1, userId = command.userId))
                .first
                .firstOrNull() ?: throw InvalidCredentialsException()
        if (!passwordHasher.verify(command.password, credential.passwordHash)) throw InvalidCredentialsException()

        return authService.sign(credential.userId)
    }
}
