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
 * Payment BC의 REST 표면. {@code GET /payments}(목록)는 인증된 요청자 id(Authentication)만 소유자 범위로 쓴다 — 이 저장소의
 * 어떤 엔드포인트도 클라이언트가 보낸 소유자 id를 신뢰하지 않는다({@code ?ownerId=} 쿼리 파라미터를 받지 않는다).
 */
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
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
    public GetPaymentResult createPayment(
            Authentication authentication, @Valid @RequestBody CreatePaymentRequest request) {
        String requesterId = authentication.getName();
        return createPaymentService.create(
                new CreatePaymentCommand(request.cardId(), request.amount(), requesterId));
    }

    @PostMapping("/{paymentId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelPayment(
            Authentication authentication,
            @PathVariable String paymentId,
            @Valid @RequestBody CancelPaymentRequest request) {
        String requesterId = authentication.getName();
        cancelPaymentService.cancel(
                new CancelPaymentCommand(paymentId, request.reason(), requesterId));
    }

    @GetMapping("/{paymentId}")
    public GetPaymentResult getPayment(
            Authentication authentication, @PathVariable String paymentId) {
        return getPaymentService.getPayment(paymentId, authentication.getName());
    }

    @GetMapping
    public GetPaymentsResult getPayments(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int take) {
        return getPaymentsService.getPayments(authentication.getName(), page, take);
    }

    @PostMapping("/{paymentId}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
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
        log.warn("결제 요청 실패", kv("code", e.code()), kv("message", e.getMessage()));
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status, e.code().name(), e.getMessage()));
    }
}
