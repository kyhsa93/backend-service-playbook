package com.example.accountservice.payment.application.command

import com.example.accountservice.payment.application.adapter.AccountAdapter
import com.example.accountservice.payment.application.adapter.CardAdapter
import com.example.accountservice.payment.domain.InsufficientBalanceException
import com.example.accountservice.payment.domain.LinkedAccountNotFoundException
import com.example.accountservice.payment.domain.LinkedCardNotFoundException
import com.example.accountservice.payment.domain.Payment
import com.example.accountservice.payment.domain.PaymentRepository
import com.example.accountservice.payment.domain.PaymentRequiresActiveAccountException
import com.example.accountservice.payment.domain.PaymentRequiresActiveCardException
import org.springframework.stereotype.Service

/**
 * Processes a payment (card + balance verification) — a combination of Adapters (synchronous reads),
 * not a Domain Service.
 *
 * 1. Synchronous Adapter → Card BC: verifies the card is ACTIVE and confirms the linked accountId
 *    (reusing the same ACL pattern that Card already uses to query Account).
 * 2. Synchronous Adapter → Account BC: verifies balance ≥ payment amount (a read-only judgment,
 *    "whether payment is possible").
 * 3. If both pass, creates the Payment and immediately transitions it to COMPLETED, publishing
 *    PaymentCompletedEvent (a Domain Event) and loading it into the Outbox.
 *
 * The actual balance deduction is not performed by this synchronous call — Account BC subscribes to
 * the `payment.completed.v1` Integration Event and performs it asynchronously (the "synchronous =
 * query, asynchronous Integration Event = state change" principle from cross-domain.md).
 */
@Service
class CreatePaymentService(
    private val paymentRepository: PaymentRepository,
    private val cardAdapter: CardAdapter,
    private val accountAdapter: AccountAdapter,
) {
    fun create(command: CreatePaymentCommand): CreatePaymentResult {
        val card = cardAdapter.findCard(command.cardId, command.requesterId) ?: throw LinkedCardNotFoundException()
        if (!card.active) throw PaymentRequiresActiveCardException()

        val account =
            accountAdapter.findAccount(card.accountId, command.requesterId) ?: throw LinkedAccountNotFoundException()
        if (!account.active) throw PaymentRequiresActiveAccountException()
        if (account.balanceAmount < command.amount) throw InsufficientBalanceException()

        val payment =
            Payment.create(cardId = command.cardId, accountId = card.accountId, ownerId = command.requesterId, amount = command.amount)
        payment.complete()

        paymentRepository.savePayment(payment)

        return CreatePaymentResult(
            paymentId = payment.paymentId,
            cardId = payment.cardId,
            accountId = payment.accountId,
            ownerId = payment.ownerId,
            amount = payment.amount,
            status = payment.status.name,
            createdAt = payment.createdAt,
        )
    }
}
