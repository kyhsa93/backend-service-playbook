package com.example.accountservice.account.application.query;

import com.example.accountservice.account.application.service.NlTransactionAnswerComposer;
import com.example.accountservice.account.application.service.NlTransactionQueryTranslator;
import com.example.accountservice.account.application.service.TransactionFilter;
import com.example.accountservice.account.domain.AccountException;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.Transaction;
import com.example.accountservice.account.domain.TransactionFindQuery;
import com.example.accountservice.account.domain.TransactionsWithCount;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A structured-data RAG pipeline (see root docs/architecture/domain-service.md), orchestrated
 * entirely in this Application-layer Query Service — never in the Controller, which only wraps the
 * HTTP request and dispatches here:
 *
 * <ol>
 *   <li><b>Retrieve-preparation</b> — {@link NlTransactionQueryTranslator} (LLM) turns the
 *       free-text question into a structured filter (type/date range).
 *   <li><b>Retrieve</b> — {@code AccountQuery.findTransactions} runs that filter, scoped to the
 *       account (an ordinary Query, no LLM involved).
 *   <li><b>Generate</b> — {@link NlTransactionAnswerComposer} (LLM) answers the question, grounded
 *       only in the retrieved records.
 * </ol>
 *
 * <p><b>Security-critical:</b> the translated filter may only narrow WHAT is returned. WHO it
 * belongs to is never taken from it — account ownership is verified up front via {@code
 * AccountQuery.findAccounts(accountId, requesterId)}, exactly like {@link GetAccountService}/{@link
 * GetTransactionsService}, using the authenticated caller's own {@code requesterId} (set by the
 * Controller from {@code Authentication}), never a value derived from the LLM's reading of free
 * text. {@link TransactionFilter} has no {@code ownerId} field to begin with. This is the lesson
 * the previous LLM-based refund feature in this repo got wrong in the other direction: it let an
 * LLM's read of untrusted free text influence a security-relevant judgment. Here, the LLM only
 * affects which of the requester's OWN transactions are shown — worst case on a bad translation is
 * an inaccurate answer about the requester's own data, never someone else's data or unauthorized
 * access.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AskTransactionHistoryService {

    // A question about "this month" or "last week" is expected to narrow well below this via the
    // translated date filter; this cap just bounds worst case (e.g. an unfiltered "show me
    // everything") so the composer's prompt stays a reasonable size.
    private static final int MAX_TRANSACTIONS_FOR_ANSWER = 50;

    private final AccountQuery accountQuery;
    private final NlTransactionQueryTranslator translator;
    private final NlTransactionAnswerComposer composer;

    public AskTransactionHistoryResult ask(String accountId, String requesterId, String question) {
        accountQuery
                .findAccounts(new AccountFindQuery(0, 1, accountId, requesterId, null))
                .accounts()
                .stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new AccountException(
                                        AccountException.ErrorCode.ACCOUNT_NOT_FOUND,
                                        "Account not found."));

        TransactionFilter filter = translator.translate(question);

        TransactionsWithCount result =
                accountQuery.findTransactions(
                        new TransactionFindQuery(
                                accountId,
                                0,
                                MAX_TRANSACTIONS_FOR_ANSWER,
                                filter.type(),
                                filter.fromDate(),
                                filter.toDate()));

        List<Transaction> transactions = result.transactions();
        List<GetTransactionsResult.TransactionSummary> summaries =
                transactions.stream()
                        .map(
                                t ->
                                        new GetTransactionsResult.TransactionSummary(
                                                t.getTransactionId(),
                                                t.getType().name(),
                                                new GetTransactionsResult.MoneyResult(
                                                        t.getAmount().amount(),
                                                        t.getAmount().currency()),
                                                t.getCreatedAt()))
                        .toList();

        String answer = composer.compose(question, summaries);
        return new AskTransactionHistoryResult(answer, result.count());
    }
}
