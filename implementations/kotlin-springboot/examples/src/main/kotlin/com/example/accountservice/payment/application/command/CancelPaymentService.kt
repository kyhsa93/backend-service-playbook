package com.example.accountservice.payment.application.command

import com.example.accountservice.payment.domain.PaymentFindQuery
import com.example.accountservice.payment.domain.PaymentNotFoundException
import com.example.accountservice.payment.domain.PaymentRepository
import org.springframework.stereotype.Service

@Service
class CancelPaymentService(
    private val paymentRepository: PaymentRepository,
) {
    fun cancel(command: CancelPaymentCommand) {
        val (payments, _) =
            paymentRepository.findPayments(
                PaymentFindQuery(page = 0, take = 1, paymentId = command.paymentId, ownerId = command.requesterId),
            )
        val payment = payments.firstOrNull() ?: throw PaymentNotFoundException(command.paymentId)

        payment.cancel(command.reason)
        paymentRepository.savePayment(payment)
        // Account BC subscribes to PaymentCancelledEvent → payment.cancelled.v1 and executes the
        // compensating credit (handled independently by OutboxPoller/OutboxConsumer — no synchronous
        // drain here).
    }
}
