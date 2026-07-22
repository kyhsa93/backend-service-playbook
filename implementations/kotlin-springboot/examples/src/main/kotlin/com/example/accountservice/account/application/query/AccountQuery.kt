package com.example.accountservice.account.application.query

import com.example.accountservice.account.domain.Account
import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.Transaction
import com.example.accountservice.account.domain.TransactionFindQuery

/**
 * A read-only port — the narrow interface the Query Service depends on.
 *
 * Follows the `<Domain>Query` naming/placement (application/query/) that root `cqrs-pattern.md`
 * specifies. Kept separate from the write model
 * ([com.example.accountservice.account.domain.AccountRepository]) so that the compiler prevents a Query
 * Service from accessing write methods like save/deleteAccount. The actual implementation
 * (AccountRepositoryImpl) implements both interfaces, but each Service is injected only with the
 * interface type it needs.
 *
 * The query methods reuse exactly the same signatures as AccountRepository (the write model)
 * (`findAccounts`/`findTransactions`) — root `repository-pattern.md`'s `find<Noun>s` unification rule
 * applies to the Query port as well (the same pattern as PaymentQuery reusing PaymentRepository's
 * `PaymentFindQuery` as-is). Single-record lookups have no dedicated method and are handled via
 * `AccountFindQuery(take = 1, ...)` + `.firstOrNull()` (see `GetAccountService`/`GetTransactionsService`).
 */
interface AccountQuery {
    fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long>

    fun findTransactions(query: TransactionFindQuery): Pair<List<Transaction>, Long>
}
