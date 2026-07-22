package com.example.accountservice.card.application.adapter;

import java.util.Optional;

/**
 * Adapter interface (Anticorruption Layer) for synchronously querying the Account BC. Issuing a
 * card requires immediately confirming the linked account's existence and active status within the
 * current request, so a synchronous Adapter pattern is used (see cross-domain.md).
 *
 * <p>The return type does not expose the Account BC's {@code AccountStatus} enum; instead it is
 * translated into the minimal shape the Card BC needs ({@link AccountView#active()}) — the purpose
 * of the ACL is to prevent upstream (Account) model changes from leaking into the Card domain. The
 * actual translation happens in infrastructure/AccountAdapterImpl.
 */
public interface AccountAdapter {

    Optional<AccountView> findAccount(String accountId, String ownerId);

    /**
     * The minimal account view owned by the Card BC — prevents Account BC's internal types from
     * leaking into Card. {@code email} is used to determine the notification recipient for the
     * monthly card usage statement (scheduling.md Feature 2, {@code SendCardStatementService}).
     */
    record AccountView(String accountId, boolean active, String email) {}
}
