package com.example.accountservice.account.interfaces.integrationevent

import com.example.accountservice.account.application.command.DepositByPaymentCommand
import com.example.accountservice.account.application.command.DepositByPaymentService
import com.example.accountservice.account.application.command.WithdrawByPaymentCommand
import com.example.accountservice.account.application.command.WithdrawByPaymentService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The Interface input adapter that receives Integration Events published by an external BC (Payment).
 *
 * Same location and role as
 * [com.example.accountservice.card.interfaces.integrationevent.CardIntegrationEventController]
 * (which subscribes to Account events) — just as Account never queries Payment via an Adapter, Payment
 * never references Account directly either. It only calls its own domain's use case (a Command Service),
 * and lets exceptions propagate as-is so that
 * [com.example.accountservice.outbox.OutboxConsumer] handles the retry (not deleting the message, so it
 * is redelivered after the SQS visibility timeout).
 */
@Component
class AccountIntegrationEventController(
    private val withdrawByPaymentService: WithdrawByPaymentService,
    private val depositByPaymentService: DepositByPaymentService,
) {
    private val logger = LoggerFactory.getLogger(AccountIntegrationEventController::class.java)

    fun onPaymentCompleted(
        paymentId: String,
        accountId: String,
        amount: Long,
    ) {
        logger
            .atInfo()
            .addKeyValue("payment_id", paymentId)
            .addKeyValue("account_id", accountId)
            .log("Received payment.completed.v1")
        withdrawByPaymentService.withdraw(
            WithdrawByPaymentCommand(accountId = accountId, amount = amount, referenceId = paymentId),
        )
    }

    fun onPaymentCancelled(
        paymentId: String,
        accountId: String,
        amount: Long,
    ) {
        logger
            .atInfo()
            .addKeyValue("payment_id", paymentId)
            .addKeyValue("account_id", accountId)
            .log("Received payment.cancelled.v1")
        depositByPaymentService.deposit(
            DepositByPaymentCommand(accountId = accountId, amount = amount, referenceId = paymentId),
        )
    }

    fun onRefundApproved(
        refundId: String,
        accountId: String,
        amount: Long,
    ) {
        logger
            .atInfo()
            .addKeyValue("refund_id", refundId)
            .addKeyValue("account_id", accountId)
            .log("Received refund.approved.v1")
        depositByPaymentService.deposit(
            DepositByPaymentCommand(accountId = accountId, amount = amount, referenceId = refundId),
        )
    }
}
