package com.example.accountservice.payment.interfaces.rest

import com.example.accountservice.payment.application.command.CancelPaymentCommand
import com.example.accountservice.payment.application.command.CancelPaymentService
import com.example.accountservice.payment.application.command.CreatePaymentCommand
import com.example.accountservice.payment.application.command.CreatePaymentResult
import com.example.accountservice.payment.application.command.CreatePaymentService
import com.example.accountservice.payment.application.command.RequestRefundCommand
import com.example.accountservice.payment.application.command.RequestRefundResult
import com.example.accountservice.payment.application.command.RequestRefundService
import com.example.accountservice.payment.application.query.GetPaymentResult
import com.example.accountservice.payment.application.query.GetPaymentService
import com.example.accountservice.payment.application.query.GetPaymentsResult
import com.example.accountservice.payment.application.query.GetPaymentsService
import com.example.accountservice.payment.application.query.GetRefundsResult
import com.example.accountservice.payment.application.query.GetRefundsService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * `GET /payments` uses only the authenticated requester ID (`authentication.name`) — this repository
 * has no endpoint that trusts a client-supplied ownerId (the same principle as
 * AccountController/CardController).
 *
 * Even when a refund request is rejected (REJECTED) by [RefundEligibilityService], this Controller
 * still responds with 201 (Created) — the refund "request" was evaluated successfully; it just so
 * happened that the result was a rejection. The rejection reason is conveyed via the
 * `status`/`decisionNote` fields (not expressed as a 4xx).
 */
@RestController
@RequestMapping("/payments")
class PaymentController(
    private val createPaymentService: CreatePaymentService,
    private val cancelPaymentService: CancelPaymentService,
    private val requestRefundService: RequestRefundService,
    private val getPaymentService: GetPaymentService,
    private val getPaymentsService: GetPaymentsService,
    private val getRefundsService: GetRefundsService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createPayment(
        authentication: Authentication,
        @Valid @RequestBody request: CreatePaymentRequest,
    ): CreatePaymentResult = createPaymentService.create(CreatePaymentCommand(request.cardId, request.amount, authentication.name))

    @PostMapping("/{paymentId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancelPayment(
        authentication: Authentication,
        @PathVariable paymentId: String,
        @Valid @RequestBody request: CancelPaymentRequest,
    ) {
        cancelPaymentService.cancel(CancelPaymentCommand(paymentId, request.reason, authentication.name))
    }

    @GetMapping("/{paymentId}")
    fun getPayment(
        authentication: Authentication,
        @PathVariable paymentId: String,
    ): GetPaymentResult = getPaymentService.getPayment(paymentId, authentication.name)

    @GetMapping
    fun getPayments(
        authentication: Authentication,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") take: Int,
    ): GetPaymentsResult = getPaymentsService.getPayments(authentication.name, page, take)

    @PostMapping("/{paymentId}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    fun requestRefund(
        authentication: Authentication,
        @PathVariable paymentId: String,
        @Valid @RequestBody request: RequestRefundRequest,
    ): RequestRefundResult =
        requestRefundService.requestRefund(
            RequestRefundCommand(paymentId, request.amount, request.reason, authentication.name),
        )

    @GetMapping("/{paymentId}/refunds")
    fun getRefunds(
        authentication: Authentication,
        @PathVariable paymentId: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") take: Int,
    ): GetRefundsResult = getRefundsService.getRefunds(paymentId, authentication.name, page, take)
}
