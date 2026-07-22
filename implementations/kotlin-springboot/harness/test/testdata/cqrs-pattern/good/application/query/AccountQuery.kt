package com.example.accountservice.account.application.query

/**
 * A read-only Query interface. Separated from the write-model AccountRepository —
 * the mention of "Repository" inside this comment is not a real code dependency, so the cqrs-pattern
 * rule must not flag it.
 */
interface AccountQuery {
    fun findByAccountIdAndOwnerId(accountId: String, ownerId: String): Account?
}
