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
    void approves_when_all_conditions_are_met() {
        Account source = fundedAccount("KRW", 10000);
        Account target = fundedAccount("KRW", 0);

        TransferDecision decision = service.evaluate(source, target, 5000);

        assertThat(decision.approved()).isTrue();
        assertThat(decision.code()).isNull();
    }

    @Test
    void rejects_when_source_and_destination_accounts_are_the_same() {
        Account source = fundedAccount("KRW", 10000);

        TransferDecision decision = service.evaluate(source, source, 5000);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code()).isEqualTo(AccountException.ErrorCode.TRANSFER_SAME_ACCOUNT);
    }

    @Test
    void rejects_when_source_account_is_inactive() {
        Account source = fundedAccount("KRW", 10000);
        source.suspend();
        Account target = fundedAccount("KRW", 0);

        TransferDecision decision = service.evaluate(source, target, 5000);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code())
                .isEqualTo(AccountException.ErrorCode.WITHDRAW_REQUIRES_ACTIVE_ACCOUNT);
    }

    @Test
    void rejects_when_target_account_is_inactive() {
        Account source = fundedAccount("KRW", 10000);
        Account target = fundedAccount("KRW", 0);
        target.suspend();

        TransferDecision decision = service.evaluate(source, target, 5000);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code())
                .isEqualTo(AccountException.ErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT);
    }

    @Test
    void rejects_on_currency_mismatch() {
        Account source = fundedAccount("KRW", 10000);
        Account target = fundedAccount("USD", 0);

        TransferDecision decision = service.evaluate(source, target, 5000);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code()).isEqualTo(AccountException.ErrorCode.CURRENCY_MISMATCH);
    }

    @Test
    void rejects_when_source_account_balance_is_insufficient() {
        Account source = fundedAccount("KRW", 1000);
        Account target = fundedAccount("KRW", 0);

        TransferDecision decision = service.evaluate(source, target, 5000);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code()).isEqualTo(AccountException.ErrorCode.INSUFFICIENT_BALANCE);
    }
}
