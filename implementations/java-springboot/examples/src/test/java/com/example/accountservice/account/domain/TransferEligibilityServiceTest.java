package com.example.accountservice.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * TransferEligibilityService is a Domain Service that coordinates rules neither Account instance
 * can decide on its own (whether the accounts are the same, whether both accounts are active,
 * currency match, and sufficient balance) — since it carries no framework annotations, it is
 * instantiated directly with {@code new} (no Spring context) to verify only the eligibility logic.
 */
class TransferEligibilityServiceTest {

    private final TransferEligibilityService service = new TransferEligibilityService();

    private Account fundedAccount(String currency, long amount) {
        Account account = Account.create("owner-1", "owner-1@example.com", currency);
        if (amount > 0) {
            account.deposit(amount);
        }
        return account;
    }

    @Test
    void 모든_조건을_만족하면_승인된다() {
        Account source = fundedAccount("KRW", 10000);
        Account target = fundedAccount("KRW", 0);

        TransferDecision decision = service.evaluate(source, target, 5000);

        assertThat(decision.approved()).isTrue();
        assertThat(decision.code()).isNull();
    }

    @Test
    void 출금_계좌와_입금_계좌가_같으면_거부된다() {
        Account source = fundedAccount("KRW", 10000);

        TransferDecision decision = service.evaluate(source, source, 5000);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code()).isEqualTo(AccountException.ErrorCode.TRANSFER_SAME_ACCOUNT);
    }

    @Test
    void 출금_계좌가_비활성이면_거부된다() {
        Account source = fundedAccount("KRW", 10000);
        source.suspend();
        Account target = fundedAccount("KRW", 0);

        TransferDecision decision = service.evaluate(source, target, 5000);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code())
                .isEqualTo(AccountException.ErrorCode.WITHDRAW_REQUIRES_ACTIVE_ACCOUNT);
    }

    @Test
    void 입금_계좌가_비활성이면_거부된다() {
        Account source = fundedAccount("KRW", 10000);
        Account target = fundedAccount("KRW", 0);
        target.suspend();

        TransferDecision decision = service.evaluate(source, target, 5000);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code())
                .isEqualTo(AccountException.ErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT);
    }

    @Test
    void 통화가_일치하지_않으면_거부된다() {
        Account source = fundedAccount("KRW", 10000);
        Account target = fundedAccount("USD", 0);

        TransferDecision decision = service.evaluate(source, target, 5000);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code()).isEqualTo(AccountException.ErrorCode.CURRENCY_MISMATCH);
    }

    @Test
    void 출금_계좌_잔액이_부족하면_거부된다() {
        Account source = fundedAccount("KRW", 1000);
        Account target = fundedAccount("KRW", 0);

        TransferDecision decision = service.evaluate(source, target, 5000);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code()).isEqualTo(AccountException.ErrorCode.INSUFFICIENT_BALANCE);
    }
}
