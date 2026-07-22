package com.example.accountservice.payment.application.adapter;

import java.util.Optional;

/**
 * Adapter interface for synchronously querying the Account BC (Anticorruption Layer). Whether a
 * payment can be made (account active + sufficient balance) must be confirmed immediately within
 * the current request, so a synchronous Adapter pattern is used. Actually deducting the balance is
 * not this synchronous query's responsibility — the Account BC subscribes to the
 * payment.completed.v1 Integration Event and performs the deduction asynchronously (the
 * "sync=query, async Integration Event=state change" principle in cross-domain.md).
 */
public interface AccountAdapter {

    Optional<AccountView> findAccount(String accountId, String ownerId);

    /**
     * The minimal account view owned by the Payment BC — unlike Card BC's
     * AccountAdapter.AccountView, this also needs the balance judgment field (balanceAmount).
     */
    record AccountView(String accountId, boolean active, long balanceAmount, String currency) {}
}
