package com.example.accountservice.payment.application.event;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.outbox.OutboxEventHandler;
import com.example.accountservice.outbox.OutboxWriter;
import com.example.accountservice.payment.application.integrationevent.PaymentCompletedIntegrationEventV1;
import com.example.accountservice.payment.domain.PaymentCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Processes the {@link PaymentCompletedEvent} (an internal Domain Event) accumulated in the Outbox,
 * translates it into the Integration Event ({@code payment.completed.v1}) that is exposed to the
 * external BC (Account), and stores it in the same Outbox transaction. The Account BC's {@code
 * PaymentCompletedIntegrationEventHandler} is automatically routed by this eventType and performs
 * the actual deduction (withdraw) — this is the same translation-point pattern as
 * account/application/event/AccountSuspendedEventHandler.java.
 */
@Component
@RequiredArgsConstructor
public class PaymentCompletedEventHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentCompletedEventHandler.class);

    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return PaymentCompletedEvent.class.getSimpleName();
    }

    @Override
    public void handle(String payload) throws Exception {
        PaymentCompletedEvent event = objectMapper.readValue(payload, PaymentCompletedEvent.class);
        log.info(
                "Payment completed",
                kv("payment_id", event.paymentId()),
                kv("account_id", event.accountId()),
                kv("amount", event.amount()));

        outboxWriter.save(
                PaymentCompletedIntegrationEventV1.EVENT_TYPE,
                new PaymentCompletedIntegrationEventV1(
                        event.paymentId(), event.accountId(), event.amount()));
    }
}
