package com.example.accountservice.account.application.event;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.account.application.command.DepositByPaymentCommand;
import com.example.accountservice.account.application.command.DepositByPaymentService;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 외부 BC(Payment)가 발행한 {@code payment.cancelled.v1} Integration Event 수신부 — 이미 차감된 금액을 되돌리는 보상
 * 크레딧(deposit)을 실행한다.
 */
@Component
@RequiredArgsConstructor
public class PaymentCancelledIntegrationEventHandler implements OutboxEventHandler {

    private static final Logger log =
            LoggerFactory.getLogger(PaymentCancelledIntegrationEventHandler.class);

    private final DepositByPaymentService depositByPaymentService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return "payment.cancelled.v1";
    }

    @Override
    public void handle(String payload) throws Exception {
        PaymentIntegrationEventPayload event =
                objectMapper.readValue(payload, PaymentIntegrationEventPayload.class);
        log.info(
                "payment.cancelled.v1 수신",
                kv("payment_id", event.paymentId()),
                kv("account_id", event.accountId()));
        depositByPaymentService.deposit(
                new DepositByPaymentCommand(event.accountId(), event.amount(), event.paymentId()));
    }
}
