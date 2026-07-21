package com.example.accountservice.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * TransferEligibilityService는 Account 어느 한쪽 인스턴스도 혼자서는 판단할 수 없는 규칙(동일 계좌 여부 + 두 계좌 활성 상태 + 통화 일치 +
 * 잔액 충분)을 조율하는 Domain Service다 — 프레임워크 애노테이션이 없으므로 Spring 컨텍스트 없이 {@code new}로 직접 인스턴스화해 판단 로직만
 * 검증한다.
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
