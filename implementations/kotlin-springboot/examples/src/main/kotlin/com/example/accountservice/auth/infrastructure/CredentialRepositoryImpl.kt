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
 * Credential 쓰기 모델([CredentialRepository])과 읽기 모델([CredentialQuery])을 함께 구현하는 구현체.
 * SignInService는 CredentialQuery 타입으로만 주입받으므로 saveCredential에는 접근할 수 없다.
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
