package com.example.accountservice.payment.application.event;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.outbox.OutboxEventHandler;
import com.example.accountservice.outbox.OutboxWriter;
import com.example.accountservice.payment.application.integrationevent.PaymentCancelledIntegrationEventV1;
import com.example.accountservice.payment.domain.PaymentCancelledEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Processes the {@link PaymentCancelledEvent} accumulated in the Outbox, translates it into the
 * {@code payment.cancelled.v1} Integration Event, and stores it. The Account BC subscribes to this
 * and runs a compensating credit (deposit) — a compensating transaction that reverses the amount
 * already deducted.
 */
@Component
@RequiredArgsConstructor
public class PaymentCancelledEventHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentCancelledEventHandler.class);

    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return PaymentCancelledEvent.class.getSimpleName();
    }

    @Override
    public void handle(String payload) throws Exception {
        PaymentCancelledEvent event = objectMapper.readValue(payload, PaymentCancelledEvent.class);
        log.info(
                "Payment cancelled",
                kv("payment_id", event.paymentId()),
                kv("account_id", event.accountId()),
                kv("reason", event.reason()));

        outboxWriter.save(
                PaymentCancelledIntegrationEventV1.EVENT_TYPE,
                new PaymentCancelledIntegrationEventV1(
                        event.paymentId(), event.accountId(), event.amount()));
    }
}
