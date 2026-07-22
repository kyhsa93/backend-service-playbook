package com.example.accountservice.payment.interfaces.rest

import com.example.accountservice.account.interfaces.rest.ErrorResponse
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
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
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
@Tag(name = "Payment")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses(
    ApiResponse(
        responseCode = "401",
        description = "The bearer token is missing, malformed, or invalid.",
        content = [Content(schema = Schema(implementation = ErrorResponse::class))],
    ),
)
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
    @Operation(
        summary = "Create a payment",
        description =
            "Charges the given card. Verifies the card is active and the linked account has a sufficient balance, " +
                "then completes the payment immediately.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "The payment was created and completed."),
        ApiResponse(
            responseCode = "400",
            description =
                "One of: card not active (`PAYMENT_REQUIRES_ACTIVE_CARD`), linked account not active " +
                    "(`PAYMENT_REQUIRES_ACTIVE_ACCOUNT`), balance insufficient (`INSUFFICIENT_BALANCE`), or validation failed (`VALIDATION_FAILED`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description =
                "One of: no card exists with the given `cardId` for this requester (`LINKED_CARD_NOT_FOUND`), or the " +
                    "account linked to that card no longer exists (`LINKED_ACCOUNT_NOT_FOUND`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun createPayment(
        authentication: Authentication,
        @Valid @RequestBody request: CreatePaymentRequest,
    ): CreatePaymentResult = createPaymentService.create(CreatePaymentCommand(request.cardId, request.amount, authentication.name))

    @PostMapping("/{paymentId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "Cancel a completed payment",
        description =
            "Cancels a payment, triggering a compensating credit back to the linked account " +
                "(handled asynchronously via the Outbox).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "The payment was cancelled."),
        ApiResponse(
            responseCode = "400",
            description =
                "One of: only a completed payment can be cancelled " +
                    "(`PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT`), or validation failed (`VALIDATION_FAILED`) — e.g. missing `reason`.",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "No payment exists with the given `paymentId` for this requester (`PAYMENT_NOT_FOUND`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun cancelPayment(
        authentication: Authentication,
        @PathVariable paymentId: String,
        @Valid @RequestBody request: CancelPaymentRequest,
    ) {
        cancelPaymentService.cancel(CancelPaymentCommand(paymentId, request.reason, authentication.name))
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Look up a payment", description = "Returns the payment only if it belongs to the authenticated requester.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The payment was found."),
        ApiResponse(
            responseCode = "404",
            description = "No payment exists with the given `paymentId` for this requester (`PAYMENT_NOT_FOUND`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun getPayment(
        authentication: Authentication,
        @PathVariable paymentId: String,
    ): GetPaymentResult = getPaymentService.getPayment(paymentId, authentication.name)

    @GetMapping
    @Operation(
        summary = "List the requester's payments",
        description = "Returns the authenticated requester's payments, newest first, paginated with `page`/`take`.",
    )
    @ApiResponse(responseCode = "200", description = "The payment list was found.")
    fun getPayments(
        authentication: Authentication,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") take: Int,
    ): GetPaymentsResult = getPaymentsService.getPayments(authentication.name, page, take)

    @PostMapping("/{paymentId}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Request a refund for a payment",
        description =
            "Evaluates a refund request against the original payment. Even a rejected request responds " +
                "201 — the rejection is conveyed via the `status`/`decisionNote` fields, not as an error, since the request " +
                "itself was evaluated successfully.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "201",
            description = "The refund request was evaluated (approved or rejected — see `status`/`decisionNote`).",
        ),
        ApiResponse(
            responseCode = "400",
            description = "Validation failed (`VALIDATION_FAILED`) — e.g. amount not positive, or a missing `reason`.",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "No payment exists with the given `paymentId` for this requester (`PAYMENT_NOT_FOUND`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun requestRefund(
        authentication: Authentication,
        @PathVariable paymentId: String,
        @Valid @RequestBody request: RequestRefundRequest,
    ): RequestRefundResult =
        requestRefundService.requestRefund(
            RequestRefundCommand(paymentId, request.amount, request.reason, authentication.name),
        )

    @GetMapping("/{paymentId}/refunds")
    @Operation(
        summary = "List a payment's refund history",
        description = "Returns the refunds requested against the given payment, newest first, paginated with `page`/`take`.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The refund history was found."),
        ApiResponse(
            responseCode = "404",
            description = "No payment exists with the given `paymentId` for this requester (`PAYMENT_NOT_FOUND`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun getRefunds(
        authentication: Authentication,
        @PathVariable paymentId: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") take: Int,
    ): GetRefundsResult = getRefundsService.getRefunds(paymentId, authentication.name, page, take)
}
