package com.example.accountservice.auth.infrastructure

import com.example.accountservice.auth.application.query.CredentialQuery
import com.example.accountservice.auth.domain.Credential
import com.example.accountservice.auth.domain.CredentialFindQuery
import com.example.accountservice.auth.domain.CredentialRepository
import com.example.accountservice.auth.infrastructure.persistence.CredentialJpaRepository
import com.example.accountservice.auth.infrastructure.persistence.CredentialMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * The implementation that provides both the Credential write model ([CredentialRepository]) and read
 * model ([CredentialQuery]). SignInService is only injected with the CredentialQuery type, so it cannot
 * access saveCredential.
 */
@Repository
class CredentialRepositoryImpl(
    private val jpaRepository: CredentialJpaRepository,
) : CredentialRepository,
    CredentialQuery {
    @Transactional
    override fun saveCredential(credential: Credential) {
        jpaRepository.save(CredentialMapper.toNewEntity(credential))
    }

    override fun findCredentials(query: CredentialFindQuery): Pair<List<Credential>, Long> {
        val credential = query.userId?.let { jpaRepository.findByUserId(it) }?.let(CredentialMapper::toDomain)
        val credentials = listOfNotNull(credential)
        return credentials to credentials.size.toLong()
    }
}
