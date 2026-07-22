package com.example.accountservice.auth.infrastructure.persistence;

import com.example.accountservice.auth.application.query.CredentialQuery;
import com.example.accountservice.auth.domain.Credential;
import com.example.accountservice.auth.domain.CredentialFindQuery;
import com.example.accountservice.auth.domain.CredentialRepository;
import com.example.accountservice.auth.domain.CredentialsWithCount;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements both the write-side {@link CredentialRepository} and the read-side {@link
 * CredentialQuery} for Credential in a single class (the same structure as
 * account/infrastructure/persistence/AccountRepositoryImpl).
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
        return jpaRepository
                .findByUserId(query.userId())
                .map(
                        entity ->
                                new CredentialsWithCount(
                                        List.of(CredentialMapper.toDomain(entity)), 1))
                .orElseGet(() -> new CredentialsWithCount(List.of(), 0));
    }
}
