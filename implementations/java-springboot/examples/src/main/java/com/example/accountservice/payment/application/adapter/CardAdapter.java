package com.example.accountservice.payment.application.adapter;

import java.util.Optional;

/**
 * Adapter interface for synchronously querying the Card BC (Anticorruption Layer). When making a
 * payment, whether the card exists and is active, and what accountId it's linked to, must be
 * confirmed immediately within the current request, so a synchronous Adapter pattern is used (see
 * cross-domain.md). Payment reuses the same pattern that Card BC already uses to query Account this
 * way.
 *
 * <p>The return type translates to the minimal shape the Payment BC needs ({@link
 * CardView#active()}) without exposing the Card BC's {@code CardStatus} enum. The actual
 * translation happens in infrastructure/CardAdapterImpl.
 */
public interface CardAdapter {

    Optional<CardView> findCard(String cardId, String ownerId);

    /**
     * The minimal card view owned by the Payment BC — keeps Card BC's internal types from leaking
     * into Payment.
     */
    record CardView(String cardId, String accountId, boolean active) {}
}
