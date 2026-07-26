package com.example.accountservice.account.application.query

import com.example.accountservice.account.application.service.NlTransactionAnswerComposer
import com.example.accountservice.account.application.service.NlTransactionQueryTranslator
import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.domain.TransactionFindQuery
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * A structured-data RAG pipeline (see root docs/architecture/domain-service.md), orchestrated
 * entirely in this Application-layer Query Service — never in the Controller, which only wraps the
 * HTTP request and dispatches here:
 *
 * 1. **Retrieve-preparation** — [NlTransactionQueryTranslator] (LLM) turns the free-text question
 *    into a structured filter (type/date range).
 * 2. **Retrieve** — `AccountQuery.findTransactions` runs that filter, scoped to the account (an
 *    ordinary Query, no LLM involved).
 * 3. **Generate** — [NlTransactionAnswerComposer] (LLM) answers the question, grounded only in the
 *    retrieved records.
 *
 * **Security-critical:** the translated filter may only narrow WHAT is returned. WHO it belongs to
 * is never taken from it — account ownership is verified up front via `accountQuery.findAccounts`,
 * exactly like [GetAccountService]/[GetTransactionsService], using the authenticated caller's own
 * `requesterId` (set by the Controller from Spring Security's `Authentication`), never a value
 * derived from the LLM's reading of free text. [com.example.accountservice.account.application.service.TransactionFilter]
 * has no `ownerId` field to begin with. This is the lesson the previous LLM-based refund feature in
 * this repo got wrong in the other direction: it let an LLM's read of untrusted free text influence
 * a security-relevant judgment. Here, the LLM only affects which of the requester's OWN transactions
 * are shown — worst case on a bad translation is an inaccurate answer about the requester's own
 * data, never someone else's data or unauthorized access.
 */
@Service
@Transactional(readOnly = true)
class AskTransactionHistoryService(
    private val accountQuery: AccountQuery,
    private val translator: NlTransactionQueryTranslator,
    private val composer: NlTransactionAnswerComposer,
) {
    // A question about "this month" or "last week" is expected to narrow well below this via the
    // translated date filter; this cap just bounds worst case (e.g. an unfiltered "show me
    // everything") so the composer's prompt stays a reasonable size.
    private val maxTransactionsForAnswer = 50

    fun ask(
        accountId: String,
        requesterId: String,
        question: String,
    ): AskTransactionHistoryResult {
        val (accounts, _) =
            accountQuery.findAccounts(
                AccountFindQuery(page = 0, take = 1, accountId = accountId, ownerId = requesterId),
            )
        accounts.firstOrNull() ?: throw AccountNotFoundException(accountId)

        val filter = translator.translate(question)

        val (transactions, count) =
            accountQuery.findTransactions(
                TransactionFindQuery(
                    accountId = accountId,
                    page = 0,
                    take = maxTransactionsForAnswer,
                    type = filter.type,
                    fromDate = filter.fromDate,
                    toDate = filter.toDate,
                ),
            )

        val summaries =
            transactions.map {
                GetTransactionsResult.TransactionSummary(
                    transactionId = it.transactionId,
                    type = it.type.name,
                    amount = GetTransactionsResult.MoneyResult(it.amount.amount, it.amount.currency),
                    createdAt = it.createdAt,
                )
            }

        val answer = composer.compose(question, summaries)
        return AskTransactionHistoryResult(answer, count)
    }
}
