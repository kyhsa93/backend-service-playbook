package com.example.accountservice.account.domain;

/**
 * The judgment result of {@link TransferEligibilityService#evaluate}. When rejected, {@code code}
 * carries, as data, the exact same {@link AccountException.ErrorCode} as when the user directly
 * calls {@code withdraw}/{@code deposit} — unlike Refund, Transfer has no persistent Aggregate of
 * its own (there is nowhere to store a rejection), so a rejection must be thrown immediately as an
 * exception, and that exception must be indistinguishable from a direct call as far as the client
 * is concerned (this has the same shape as {@code RefundDecision}, but differs in that Refund's
 * REJECTED is a valid, persisted domain outcome, whereas Transfer's rejection leads to an
 * exception).
 */
public record TransferDecision(boolean approved, AccountException.ErrorCode code, String reason) {

    public static TransferDecision approve() {
        return new TransferDecision(true, null, null);
    }

    public static TransferDecision rejected(AccountException.ErrorCode code, String reason) {
        return new TransferDecision(false, code, reason);
    }
}
