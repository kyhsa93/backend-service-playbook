package com.example.accountservice.auth.application.query

import com.example.accountservice.auth.domain.Credential
import com.example.accountservice.auth.domain.CredentialFindQuery

/**
 * The Credential read-only port — both checking for a duplicate user ID (SignUpService) and looking up
 * the hash for password verification (SignInService) go through this interface. SignInService never
 * saves credentials (it only reads then verifies), so it doesn't access the write model
 * [com.example.accountservice.auth.domain.CredentialRepository].
 *
 * The query method follows the root `repository-pattern.md`'s `find<Noun>s` naming convention — rather
 * than having a dedicated single-record lookup method (like `findByUserId`), it's handled with
 * `CredentialFindQuery(take = 1, userId = ...)` + `.first.firstOrNull()` (the same pattern as
 * AccountQuery/CardQuery/PaymentQuery).
 */
interface CredentialQuery {
    fun findCredentials(query: CredentialFindQuery): Pair<List<Credential>, Long>
}
