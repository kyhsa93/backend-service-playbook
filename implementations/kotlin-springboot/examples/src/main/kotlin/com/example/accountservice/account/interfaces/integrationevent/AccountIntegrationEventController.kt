package com.example.accountservice.account.interfaces.integrationevent

import com.example.accountservice.account.application.command.DepositByPaymentCommand
import com.example.accountservice.account.application.command.DepositByPaymentService
import com.example.accountservice.account.application.command.WithdrawByPaymentCommand
import com.example.accountservice.account.application.command.WithdrawByPaymentService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 외부 BC(Payment)가 발행한 Integration Event를 수신하는 Interface 입력 어댑터.
 *
 * [com.example.accountservice.card.interfaces.integrationevent.CardIntegrationEventController]
 * (Account 이벤트 구독)와 동일한 위치·역할이다 — Account가 Payment를 Adapter로 조회하지 않는
 * 것처럼, Payment도 Account를 직접 참조하지 않는다. 자기 도메인의 유스케이스(Command Service)만
 * 호출하고, 예외는 그대로 던져 [com.example.accountservice.outbox.OutboxRelay]가 재시도를 담당하게
 * 한다.
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
            .log("payment.completed.v1 수신")
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
            .log("payment.cancelled.v1 수신")
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
            .log("refund.approved.v1 수신")
        depositByPaymentService.deposit(
            DepositByPaymentCommand(accountId = accountId, amount = amount, referenceId = refundId),
        )
    }
}
