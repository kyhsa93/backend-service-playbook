package com.example.accountservice.payment.application.command

import com.example.accountservice.outbox.OutboxRelay
import com.example.accountservice.payment.domain.PaymentFindQuery
import com.example.accountservice.payment.domain.PaymentNotFoundException
import com.example.accountservice.payment.domain.PaymentRepository
import org.springframework.stereotype.Service

@Service
class CancelPaymentService(
    private val paymentRepository: PaymentRepository,
    private val outboxRelay: OutboxRelay,
) {
    fun cancel(command: CancelPaymentCommand) {
        val (payments, _) =
            paymentRepository.findPayments(
                PaymentFindQuery(page = 0, take = 1, paymentId = command.paymentId, ownerId = command.requesterId),
            )
        val payment = payments.firstOrNull() ?: throw PaymentNotFoundException(command.paymentId)

        payment.cancel(command.reason)
        paymentRepository.savePayment(payment)
        // PaymentCancelledEvent → payment.cancelled.v1을 Account BC가 구독해 보상 크레딧을 실행한다.
        outboxRelay.processPending()
    }
}
