package com.example.accountservice.account.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accountservice.account.application.service.NlTransactionAnswerComposer;
import com.example.accountservice.account.application.service.NlTransactionQueryTranslator;
import com.example.accountservice.account.application.service.TransactionFilter;
import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountException;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountsWithCount;
import com.example.accountservice.account.domain.Money;
import com.example.accountservice.account.domain.Transaction;
import com.example.accountservice.account.domain.TransactionFindQuery;
import com.example.accountservice.account.domain.TransactionType;
import com.example.accountservice.account.domain.TransactionsWithCount;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AskTransactionHistoryServiceTest {

    @Mock private AccountQuery accountQuery;
    @Mock private NlTransactionQueryTranslator translator;
    @Mock private NlTransactionAnswerComposer composer;

    private AskTransactionHistoryService service;

    @BeforeEach
    void setUp() {
        service = new AskTransactionHistoryService(accountQuery, translator, composer);
    }

    @Test
    void
            ask_when_called_then_scopes_the_lookup_to_the_requester_never_to_a_value_from_the_translated_filter() {
        Account account = Account.create("owner-1", "owner-1@example.com", "KRW");
        when(accountQuery.findAccounts(
                        new AccountFindQuery(0, 1, account.getAccountId(), "owner-1", null)))
                .thenReturn(new AccountsWithCount(List.of(account), 1));
        when(translator.translate(anyString()))
                .thenReturn(
                        new TransactionFilter(
                                TransactionType.WITHDRAWAL,
                                LocalDate.of(2026, 7, 1),
                                LocalDate.of(2026, 7, 31)));
        when(accountQuery.findTransactions(any(TransactionFindQuery.class)))
                .thenReturn(new TransactionsWithCount(List.of(), 0));
        when(composer.compose(anyString(), anyList()))
                .thenReturn("No matching transactions were found.");

        service.ask(account.getAccountId(), "owner-1", "How much did I withdraw in July?");

        // ownerId always comes from the authenticated requester (used to verify account
        // ownership up front), never from the translated filter — TransactionFilter has no
        // ownerId field to begin with, but this pins the intent explicitly.
        verify(accountQuery)
                .findAccounts(new AccountFindQuery(0, 1, account.getAccountId(), "owner-1", null));
        verify(accountQuery)
                .findTransactions(
                        new TransactionFindQuery(
                                account.getAccountId(),
                                0,
                                50,
                                TransactionType.WITHDRAWAL,
                                LocalDate.of(2026, 7, 1),
                                LocalDate.of(2026, 7, 31)));
    }

    @Test
    void
            ask_when_called_then_composes_the_answer_from_the_retrieved_transactions_and_returns_the_match_count() {
        Account account = Account.create("owner-1", "owner-1@example.com", "KRW");
        when(accountQuery.findAccounts(
                        new AccountFindQuery(0, 1, account.getAccountId(), "owner-1", null)))
                .thenReturn(new AccountsWithCount(List.of(account), 1));
        Transaction transaction =
                Transaction.reconstitute(
                        "t1",
                        account.getAccountId(),
                        TransactionType.DEPOSIT,
                        new Money(1000, "KRW"),
                        null,
                        LocalDateTime.now());
        when(translator.translate(anyString())).thenReturn(new TransactionFilter(null, null, null));
        when(accountQuery.findTransactions(any(TransactionFindQuery.class)))
                .thenReturn(new TransactionsWithCount(List.of(transaction), 1));
        when(composer.compose(anyString(), anyList())).thenReturn("You deposited 1000 KRW.");

        AskTransactionHistoryResult result =
                service.ask(account.getAccountId(), "owner-1", "How much did I deposit?");

        assertThat(result).isEqualTo(new AskTransactionHistoryResult("You deposited 1000 KRW.", 1));
    }

    @Test
    void
            ask_when_the_account_does_not_belong_to_the_requester_then_throws_without_calling_the_translator_or_composer() {
        when(accountQuery.findAccounts(new AccountFindQuery(0, 1, "account-1", "owner-1", null)))
                .thenReturn(new AccountsWithCount(List.of(), 0));

        assertThatThrownBy(() -> service.ask("account-1", "owner-1", "anything"))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.ACCOUNT_NOT_FOUND);
        verify(translator, never()).translate(anyString());
        verify(composer, never()).compose(anyString(), anyList());
    }
}
