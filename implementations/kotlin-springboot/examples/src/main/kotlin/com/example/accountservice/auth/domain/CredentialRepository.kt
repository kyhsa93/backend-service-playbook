package com.example.accountservice.auth.domain

/**
 * The Credential write-model port — handles only saving.
 *
 * Reads (checking for a duplicate user ID, looking up the hash for password verification) are separated
 * out into [com.example.accountservice.auth.application.query.CredentialQuery] (cqrs-pattern.md). The
 * actual implementation (CredentialRepositoryImpl) implements both interfaces, but each Service is only
 * injected with the interface type it needs — SignInService only reads, so it depends only on
 * CredentialQuery and cannot access this write port.
 */
interface CredentialRepository {
    fun saveCredential(credential: Credential)
}

/**
 * Defined to follow the root `repository-pattern.md`'s `find<Noun>s` naming convention —
 * [com.example.accountservice.auth.application.query.CredentialQuery] reuses this type to define
 * `findCredentials` (the same pattern as AccountFindQuery/PaymentFindQuery). Since userId is a unique
 * key, the result is at most one row, but it still has page/take like any other Find Query.
 */
data class CredentialFindQuery(
    val page: Int,
    val take: Int,
    val userId: String? = null,
)
