package com.example.accountservice.auth.domain;

/**
 * The write-side Repository contract for the Credential Aggregate (owned by domain). Read-only
 * lookups are separated into the dedicated application/query/CredentialQuery interface (see
 * cqrs-pattern.md). Since a Credential is an immutable record never modified after creation
 * (sign-up), there is no write method other than save.
 */
public interface CredentialRepository {
    void saveCredential(Credential credential);
}
