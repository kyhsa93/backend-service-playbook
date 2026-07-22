package com.example.accountservice.payment.application.command;

import com.example.accountservice.payment.application.adapter.AccountAdapter;
import com.example.accountservice.payment.application.adapter.CardAdapter;
import com.example.accountservice.payment.application.query.GetPaymentResult;
import com.example.accountservice.payment.domain.Payment;
import com.example.accountservice.payment.domain.PaymentException;
import com.example.accountservice.payment.domain.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CreatePaymentService {

    private final PaymentRepository paymentRepository;
    private final CardAdapter cardAdapter;
    private final AccountAdapter accountAdapter;

    public GetPaymentResult create(CreatePaymentCommand command) {
        // Confirm via the synchronous Adapter (ACL) that the card exists and is active — a
        // synchronous call is needed because the response (whether the payment can proceed)
        // depends on it.
        CardAdapter.CardView card =
                cardAdapter
                        .findCard(command.cardId(), command.requesterId())
                        .orElseThrow(
                                () ->
                                        new PaymentException(
                                                PaymentException.ErrorCode.LINKED_CARD_NOT_FOUND,
                                                "The card to link could not be found."));
        if (!card.active()) {
            throw new PaymentException(
                    PaymentException.ErrorCode.PAYMENT_REQUIRES_ACTIVE_CARD,
                    "Only an active card can be used for payment.");
        }

        // Confirm via the synchronous Adapter (ACL) that the linked account is active and has a
        // sufficient balance (a read-only judgment). The actual deduction does not happen here —
        // the Account BC subscribes to PaymentCompletedEvent -> payment.completed.v1 and performs
        // it asynchronously (the "sync=query, async=state change" principle in cross-domain.md).
        AccountAdapter.AccountView account =
                accountAdapter
                        .findAccount(card.accountId(), command.requesterId())
                        .orElseThrow(
                                () ->
                                        new PaymentException(
                                                PaymentException.ErrorCode.LINKED_ACCOUNT_NOT_FOUND,
                                                "The linked account could not be found."));
        if (!account.active()) {
            throw new PaymentException(
                    PaymentException.ErrorCode.PAYMENT_REQUIRES_ACTIVE_ACCOUNT,
                    "Only an active account can be used for payment.");
        }
        if (account.balanceAmount() < command.amount()) {
            throw new PaymentException(
                    PaymentException.ErrorCode.INSUFFICIENT_BALANCE,
                    "Cannot make the payment because the account balance is insufficient.");
        }

        Payment payment =
                Payment.create(
                        command.cardId(),
                        card.accountId(),
                        command.requesterId(),
                        command.amount());
        payment.complete();

        paymentRepository.savePayment(payment);
        return GetPaymentResult.from(payment);
    }
}
