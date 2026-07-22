package com.example.accountservice.payment.interfaces.rest;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.account.interfaces.rest.ErrorResponse;
import com.example.accountservice.payment.application.command.CancelPaymentCommand;
import com.example.accountservice.payment.application.command.CancelPaymentService;
import com.example.accountservice.payment.application.command.CreatePaymentCommand;
import com.example.accountservice.payment.application.command.CreatePaymentService;
import com.example.accountservice.payment.application.command.RequestRefundCommand;
import com.example.accountservice.payment.application.command.RequestRefundService;
import com.example.accountservice.payment.application.query.GetPaymentResult;
import com.example.accountservice.payment.application.query.GetPaymentService;
import com.example.accountservice.payment.application.query.GetPaymentsResult;
import com.example.accountservice.payment.application.query.GetPaymentsService;
import com.example.accountservice.payment.application.query.GetRefundResult;
import com.example.accountservice.payment.application.query.GetRefundsResult;
import com.example.accountservice.payment.application.query.GetRefundsService;
import com.example.accountservice.payment.domain.PaymentException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The REST surface of the Payment BC. {@code GET /payments} (the list) scopes ownership using only
 * the authenticated requester id (Authentication) — no endpoint in this codebase trusts an owner id
 * sent by the client (it does not accept an {@code ?ownerId=} query parameter).
 */
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Tag(name = "Payment")
@SecurityRequirement(name = "bearer-jwt")
@ApiResponse(
        responseCode = "401",
        description = "The bearer token is missing, malformed, or invalid.",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private static final Set<PaymentException.ErrorCode> NOT_FOUND_CODES =
            Set.of(
                    PaymentException.ErrorCode.PAYMENT_NOT_FOUND,
                    PaymentException.ErrorCode.LINKED_CARD_NOT_FOUND,
                    PaymentException.ErrorCode.LINKED_ACCOUNT_NOT_FOUND);

    private final CreatePaymentService createPaymentService;
    private final CancelPaymentService cancelPaymentService;
    private final RequestRefundService requestRefundService;
    private final GetPaymentService getPaymentService;
    private final GetPaymentsService getPaymentsService;
    private final GetRefundsService getRefundsService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Create a payment",
            description =
                    "Charges the given card for the given amount. The linked card and account must"
                            + " both be active, and the account must have a sufficient balance —"
                            + " the actual balance debit happens asynchronously once this payment's"
                            + " completion event is processed.")
    @ApiResponse(
            responseCode = "201",
            description = "The payment was created.",
            content = @Content(schema = @Schema(implementation = GetPaymentResult.class)))
    @ApiResponse(
            responseCode = "400",
            description =
                    "One of: only an active card can be used for payment"
                            + " (`PAYMENT_REQUIRES_ACTIVE_CARD`), only an active account can be used"
                            + " for payment (`PAYMENT_REQUIRES_ACTIVE_ACCOUNT`), the account balance"
                            + " is insufficient (`INSUFFICIENT_BALANCE`), or request validation"
                            + " failed (`VALIDATION_FAILED`) — e.g. a missing cardId or non-positive"
                            + " amount.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description =
                    "One of: no card exists with the given `cardId` (`LINKED_CARD_NOT_FOUND`), or"
                            + " no account is linked to that card (`LINKED_ACCOUNT_NOT_FOUND`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public GetPaymentResult createPayment(
            Authentication authentication, @Valid @RequestBody CreatePaymentRequest request) {
        String requesterId = authentication.getName();
        return createPaymentService.create(
                new CreatePaymentCommand(request.cardId(), request.amount(), requesterId));
    }

    @PostMapping("/{paymentId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Cancel a payment",
            description =
                    "Reverses an already-completed payment. Only a `COMPLETED` payment can be"
                            + " cancelled — the compensating account credit happens asynchronously"
                            + " once this cancellation event is processed.")
    @ApiResponse(responseCode = "204", description = "The payment was cancelled.")
    @ApiResponse(
            responseCode = "400",
            description =
                    "One of: only a completed payment can be cancelled"
                            + " (`PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT`), or request validation"
                            + " failed (`VALIDATION_FAILED`) — e.g. a missing reason.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description =
                    "No payment exists with the given `paymentId` for this requester (`PAYMENT_NOT_FOUND`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public void cancelPayment(
            Authentication authentication,
            @PathVariable String paymentId,
            @Valid @RequestBody CancelPaymentRequest request) {
        String requesterId = authentication.getName();
        cancelPaymentService.cancel(
                new CancelPaymentCommand(paymentId, request.reason(), requesterId));
    }

    @GetMapping("/{paymentId}")
    @Operation(
            summary = "Look up a payment",
            description = "Returns the payment only if it belongs to the authenticated requester.")
    @ApiResponse(
            responseCode = "200",
            description = "The payment was found.",
            content = @Content(schema = @Schema(implementation = GetPaymentResult.class)))
    @ApiResponse(
            responseCode = "404",
            description =
                    "No payment exists with the given `paymentId` for this requester (`PAYMENT_NOT_FOUND`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public GetPaymentResult getPayment(
            Authentication authentication, @PathVariable String paymentId) {
        return getPaymentService.getPayment(paymentId, authentication.getName());
    }

    @GetMapping
    @Operation(
            summary = "List the authenticated requester's payments",
            description =
                    "Returns the requester's payments, newest first, paginated with `page`/`take`.")
    @ApiResponse(
            responseCode = "200",
            description = "The payment history was found.",
            content = @Content(schema = @Schema(implementation = GetPaymentsResult.class)))
    public GetPaymentsResult getPayments(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int take) {
        return getPaymentsService.getPayments(authentication.getName(), page, take);
    }

    @PostMapping("/{paymentId}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Request a refund for a payment",
            description =
                    "Requests a refund against a completed payment. A rejected refund is a valid"
                            + " business outcome, not an error — this endpoint still returns 201"
                            + " with `status: REJECTED` when the original payment isn't `COMPLETED`"
                            + " or the refund amount exceeds the payment amount; the account credit"
                            + " for an approved refund happens asynchronously once the approval"
                            + " event is processed.")
    @ApiResponse(
            responseCode = "201",
            description = "The refund request was recorded, either `APPROVED` or `REJECTED`.",
            content = @Content(schema = @Schema(implementation = GetRefundResult.class)))
    @ApiResponse(
            responseCode = "400",
            description =
                    "Request validation failed (`VALIDATION_FAILED`) — e.g. a missing reason or non-positive amount.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description =
                    "No payment exists with the given `paymentId` for this requester (`PAYMENT_NOT_FOUND`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public GetRefundResult requestRefund(
            Authentication authentication,
            @PathVariable String paymentId,
            @Valid @RequestBody RequestRefundRequest request) {
        String requesterId = authentication.getName();
        return requestRefundService.request(
                new RequestRefundCommand(
                        paymentId, request.amount(), request.reason(), requesterId));
    }

    @GetMapping("/{paymentId}/refunds")
    @Operation(
            summary = "List a payment's refund history",
            description =
                    "Returns the payment's refund requests, newest first, paginated with `page`/`take`.")
    @ApiResponse(
            responseCode = "200",
            description = "The refund history was found.",
            content = @Content(schema = @Schema(implementation = GetRefundsResult.class)))
    @ApiResponse(
            responseCode = "404",
            description =
                    "No payment exists with the given `paymentId` for this requester (`PAYMENT_NOT_FOUND`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public GetRefundsResult getRefunds(
            Authentication authentication,
            @PathVariable String paymentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int take) {
        return getRefundsService.getRefunds(paymentId, authentication.getName(), page, take);
    }

    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<ErrorResponse> handlePaymentException(PaymentException e) {
        HttpStatus status =
                NOT_FOUND_CODES.contains(e.code()) ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        log.warn("Payment request failed", kv("code", e.code()), kv("message", e.getMessage()));
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status, e.code().name(), e.getMessage()));
    }
}
