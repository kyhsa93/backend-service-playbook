package com.example.accountservice.auth.application.query;

import com.example.accountservice.auth.domain.CredentialFindQuery;
import com.example.accountservice.auth.domain.CredentialsWithCount;

/**
 * The read-only lookup contract for Credential (the same role as AccountQuery/CardQuery in
 * cqrs-pattern.md). Both Command Services — SignUpService (checking for a duplicate ID) and
 * SignInService (looking up the stored hash for password verification) — use only this Query. Since
 * a Credential is never modified after creation, a read-only Query suffices for both use cases
 * instead of the write-side CredentialRepository.
 */
public interface CredentialQuery {
    CredentialsWithCount findCredentials(CredentialFindQuery query);
}
