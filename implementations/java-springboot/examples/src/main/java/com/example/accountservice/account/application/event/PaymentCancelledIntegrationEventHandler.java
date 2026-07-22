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
 * The receiver for the {@code payment.cancelled.v1} Integration Event published by the external BC
 * (Payment) — it performs the compensating credit (deposit) that reverses an amount already
 * deducted.
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
                "payment.cancelled.v1 received",
                kv("payment_id", event.paymentId()),
                kv("account_id", event.accountId()));
        depositByPaymentService.deposit(
                new DepositByPaymentCommand(event.accountId(), event.amount(), event.paymentId()));
    }
}
