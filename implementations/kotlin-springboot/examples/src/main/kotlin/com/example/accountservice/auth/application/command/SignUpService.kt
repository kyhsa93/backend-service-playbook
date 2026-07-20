package com.example.accountservice.auth.application.command

import com.example.accountservice.auth.application.query.CredentialQuery
import com.example.accountservice.auth.application.service.PasswordHasher
import com.example.accountservice.auth.domain.Credential
import com.example.accountservice.auth.domain.CredentialFindQuery
import com.example.accountservice.auth.domain.CredentialRepository
import com.example.accountservice.auth.domain.UserIdAlreadyExistsException
import org.springframework.stereotype.Service

@Service
class SignUpService(
    private val credentialQuery: CredentialQuery,
    private val credentialRepository: CredentialRepository,
    private val passwordHasher: PasswordHasher,
) {
    fun signUp(command: SignUpCommand) {
        credentialQuery
            .findCredentials(CredentialFindQuery(page = 0, take = 1, userId = command.userId))
            .first
            .firstOrNull()
            ?.let { throw UserIdAlreadyExistsException() }

        val passwordHash = passwordHasher.hash(command.password)
        credentialRepository.saveCredential(Credential.create(command.userId, passwordHash))
    }
}
