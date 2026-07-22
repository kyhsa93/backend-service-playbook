package com.example.accountservice.card.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.accountservice.card.application.adapter.AccountAdapter;
import com.example.accountservice.card.domain.CardException;
import com.example.accountservice.card.domain.CardRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IssueCardServiceTest {

    @Mock private CardRepository cardRepository;

    @Mock private AccountAdapter accountAdapter;

    private IssueCardService service;

    @BeforeEach
    void setUp() {
        service = new IssueCardService(cardRepository, accountAdapter);
    }

    @Test
    void issues_and_saves_a_card_when_linked_account_is_active() {
        when(accountAdapter.findAccount("account-1", "owner-1"))
                .thenReturn(
                        Optional.of(
                                new AccountAdapter.AccountView(
                                        "account-1", true, "owner-1@example.com")));

        IssueCardResult result =
                service.issue(new IssueCardCommand("account-1", "VISA", "owner-1"));

        assertThat(result.accountId()).isEqualTo("account-1");
        assertThat(result.ownerId()).isEqualTo("owner-1");
        assertThat(result.brand()).isEqualTo("VISA");
        assertThat(result.status()).isEqualTo("ACTIVE");
        verify(cardRepository).saveCard(any());
    }

    @Test
    void throws_exception_and_does_not_save_when_linked_account_is_not_found() {
        when(accountAdapter.findAccount("account-1", "owner-1")).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () -> service.issue(new IssueCardCommand("account-1", "VISA", "owner-1")))
                .isInstanceOf(CardException.class)
                .extracting(e -> ((CardException) e).code())
                .isEqualTo(CardException.ErrorCode.LINKED_ACCOUNT_NOT_FOUND);
        verifyNoInteractions(cardRepository);
    }

    @Test
    void throws_exception_and_does_not_save_when_linked_account_is_inactive() {
        when(accountAdapter.findAccount("account-1", "owner-1"))
                .thenReturn(
                        Optional.of(
                                new AccountAdapter.AccountView(
                                        "account-1", false, "owner-1@example.com")));

        assertThatThrownBy(
                        () -> service.issue(new IssueCardCommand("account-1", "VISA", "owner-1")))
                .isInstanceOf(CardException.class)
                .extracting(e -> ((CardException) e).code())
                .isEqualTo(CardException.ErrorCode.CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT);
        verifyNoInteractions(cardRepository);
    }
}
