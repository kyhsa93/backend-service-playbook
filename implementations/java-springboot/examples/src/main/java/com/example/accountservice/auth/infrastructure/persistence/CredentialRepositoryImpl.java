package com.example.accountservice.auth.infrastructure.persistence;

import com.example.accountservice.auth.application.query.CredentialQuery;
import com.example.accountservice.auth.domain.Credential;
import com.example.accountservice.auth.domain.CredentialFindQuery;
import com.example.accountservice.auth.domain.CredentialRepository;
import com.example.accountservice.auth.domain.CredentialsWithCount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Credential의 쓰기용 {@link CredentialRepository}와 읽기용 {@link CredentialQuery}를 한
 * 클래스에서 구현한다 (account/infrastructure/persistence/AccountRepositoryImpl과 동일한 구조).
 */
@Repository
@RequiredArgsConstructor
public class CredentialRepositoryImpl implements CredentialRepository, CredentialQuery {

    private final CredentialJpaRepository jpaRepository;

    @Override
    @Transactional
    public void saveCredential(Credential credential) {
        jpaRepository.save(CredentialMapper.toNewEntity(credential));
    }

    @Override
    public CredentialsWithCount findCredentials(CredentialFindQuery query) {
        if (query.userId() == null || query.userId().isBlank()) {
            return new CredentialsWithCount(List.of(), 0);
        }
        return jpaRepository.findByUserId(query.userId())
                .map(entity -> new CredentialsWithCount(List.of(CredentialMapper.toDomain(entity)), 1))
                .orElseGet(() -> new CredentialsWithCount(List.of(), 0));
    }
}
