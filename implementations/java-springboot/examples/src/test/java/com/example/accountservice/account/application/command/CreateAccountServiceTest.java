package com.example.accountservice.account.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.example.accountservice.account.domain.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CreateAccountServiceTest {

    @Mock private AccountRepository accountRepository;

    private CreateAccountService service;

    @BeforeEach
    void setUp() {
        service = new CreateAccountService(accountRepository);
    }

    @Test
    void creating_account_saves_it_and_the_result_carries_an_initial_zero_balance() {
        CreateAccountResult result =
                service.create(new CreateAccountCommand("owner-1", "owner-1@example.com", "KRW"));

        assertThat(result.ownerId()).isEqualTo("owner-1");
        assertThat(result.balance().amount()).isEqualTo(0);
        verify(accountRepository).saveAccount(any());
    }
}
