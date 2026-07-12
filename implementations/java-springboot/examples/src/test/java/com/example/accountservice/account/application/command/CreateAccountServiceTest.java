package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.outbox.OutboxRelay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CreateAccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private OutboxRelay outboxRelay;

    private CreateAccountService service;

    @BeforeEach
    void setUp() {
        service = new CreateAccountService(accountRepository, outboxRelay);
    }

    @Test
    void 계좌_생성_시_저장되고_결과에_초기_잔액_0이_담긴다() {
        CreateAccountResult result = service.create(new CreateAccountCommand("owner-1", "owner-1@example.com", "KRW"));

        assertThat(result.ownerId()).isEqualTo("owner-1");
        assertThat(result.balance().amount()).isEqualTo(0);
        verify(accountRepository).saveAccount(any());
    }

    @Test
    void 계좌_저장_직후_OutboxRelay가_드레인을_한_번_호출한다() {
        service.create(new CreateAccountCommand("owner-1", "owner-1@example.com", "KRW"));

        verify(outboxRelay).processPending();
    }
}
