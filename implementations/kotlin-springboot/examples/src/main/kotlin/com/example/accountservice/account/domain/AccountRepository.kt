package com.example.accountservice.account.domain

import java.time.LocalDate

interface AccountRepository {
    fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long>

    fun saveAccount(account: Account)

    /**
     * Saves both the source and target Account in a single physical transaction — used only for use
     * cases, like Transfer (an inter-account funds transfer), where saves to two different Account
     * instances must be committed together or rolled back together atomically. Existing use cases that
     * only need to save a single Account keep using [saveAccount] (persistence.md — the transaction
     * boundary lives in this Repository's save* methods, not in the Command Service).
     */
    fun saveAccounts(
        source: Account,
        target: Account,
    )

    fun deleteAccount(accountId: String)

    fun findTransactions(query: TransactionFindQuery): Pair<List<Transaction>, Long>

    /**
     * An idempotency check ensuring the Payment BC's Integration Event reactions
     * (WithdrawByPaymentService/DepositByPaymentService) don't create the same transaction twice even on
     * an at-least-once redelivery (Level 2 Ledger — see domain-events.md). Unlike Card's state-based
     * idempotency (re-suspending an already-suspended card is harmless), moving money produces a
     * different result each time it is applied, so an explicit already-processed check is required.
     *
     * [type] must also be checked — a completed payment (WITHDRAWAL) and its cancellation compensation
     * credit (DEPOSIT) are different transactions that share the same paymentId as referenceId, so
     * checking by referenceId alone would incorrectly judge the compensation credit as "already
     * processed" and skip it.
     */
    fun hasTransactionWithReference(
        referenceId: String,
        type: TransactionType,
    ): Boolean
}

data class AccountFindQuery(
    val page: Int,
    val take: Int,
    val accountId: String? = null,
    val ownerId: String? = null,
    val status: List<String>? = null,
    // A filter used only by PayInterestService — filters out, at the query stage, accounts that have
    // already received interest on this date (lastInterestPaidAt = this value). Same intent as Card's
    // CardFindQuery.excludeStatementMonth: rather than skipping already-processed rows in an
    // Application-layer loop, the query itself filters them out.
    val excludeInterestPaidDate: LocalDate? = null,
)

data class TransactionFindQuery(
    val accountId: String,
    val page: Int,
    val take: Int,
)
