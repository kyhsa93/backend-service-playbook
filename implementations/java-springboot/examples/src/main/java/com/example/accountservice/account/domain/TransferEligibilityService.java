package com.example.accountservice.account.domain;

/**
 * Domain Service — a pure class with no framework annotations (not registered as a Spring bean; the
 * Application layer creates it directly with {@code new} when needed. See root
 * docs/architecture/domain-service.md, for the same reason as {@link
 * com.example.accountservice.payment.domain.RefundEligibilityService}).
 *
 * <p>The judgment "the source and destination accounts are different, both are active, the
 * currencies match, and the source account's balance is sufficient" cannot be made from either
 * {@link Account} instance alone — both Aggregate instances must be loaded and compared together.
 */
public class TransferEligibilityService {

    public TransferDecision evaluate(Account source, Account target, long amount) {
        if (source.getAccountId().equals(target.getAccountId())) {
            return TransferDecision.rejected(
                    AccountException.ErrorCode.TRANSFER_SAME_ACCOUNT,
                    "The source and destination accounts cannot be the same.");
        }
        if (source.getStatus() != AccountStatus.ACTIVE) {
            return TransferDecision.rejected(
                    AccountException.ErrorCode.WITHDRAW_REQUIRES_ACTIVE_ACCOUNT,
                    "Only an active account can make withdrawals.");
        }
        if (target.getStatus() != AccountStatus.ACTIVE) {
            return TransferDecision.rejected(
                    AccountException.ErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT,
                    "Only an active account can receive deposits.");
        }
        if (!source.getBalance().currency().equals(target.getBalance().currency())) {
            return TransferDecision.rejected(
                    AccountException.ErrorCode.CURRENCY_MISMATCH, "Currency mismatch.");
        }
        if (source.getBalance().amount() < amount) {
            return TransferDecision.rejected(
                    AccountException.ErrorCode.INSUFFICIENT_BALANCE, "Insufficient balance.");
        }
        return TransferDecision.approve();
    }
}
