package com.example.accountservice.account.domain;

public interface AccountRepository {
    AccountsWithCount findAccounts(AccountFindQuery query);

    void saveAccount(Account account);

    /**
     * Saves the source/target Accounts as a single physical transaction — this is dedicated to use
     * cases like Transfer, where the saves for two distinct Account instances must be committed
     * together or rolled back together atomically. Existing use cases that only need to save a
     * single Account continue to use {@link #saveAccount} (persistence.md — the transaction
     * boundary lives in this Repository's save* methods, not in the Command Service).
     */
    void saveAccounts(Account source, Account target);

    void deleteAccount(String accountId);

    TransactionsWithCount findTransactions(String accountId, int page, int take);

    /**
     * An idempotency check ensuring that the Payment BC's Integration Event reactions
     * (WithdrawByPaymentService/DepositByPaymentService) do not create the same transaction twice
     * even under at-least-once redelivery (Level 2 Ledger — see domain-events.md). Unlike Card's
     * status-based idempotency (re-suspending an already-suspended card is harmless), moving money
     * produces a different result each time it is applied, so a separate "already processed" check
     * is required.
     *
     * <p>{@code type} must be checked together with the reference — a payment-completed transaction
     * (WITHDRAWAL) and its payment-cancelled compensating credit (DEPOSIT) are distinct
     * transactions that share the same paymentId as their referenceId, so checking referenceId
     * alone would incorrectly judge the compensating credit as "already processed" and skip it.
     */
    boolean hasTransactionWithReference(String referenceId, TransactionType type);
}
