package com.example.accountservice.payment.application.event;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.outbox.OutboxEventHandler;
import com.example.accountservice.outbox.OutboxWriter;
import com.example.accountservice.payment.application.integrationevent.RefundApprovedIntegrationEventV1;
import com.example.accountservice.payment.domain.RefundApprovedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Processes the {@link RefundApprovedEvent} accumulated in the Outbox, translates it into the
 * {@code refund.approved.v1} Integration Event, and stores it. The Account BC subscribes to this
 * and runs the refund credit (deposit).
 */
@Component
@RequiredArgsConstructor
public class RefundApprovedEventHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(RefundApprovedEventHandler.class);

    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return RefundApprovedEvent.class.getSimpleName();
    }

    @Override
    public void handle(String payload) throws Exception {
        RefundApprovedEvent event = objectMapper.readValue(payload, RefundApprovedEvent.class);
        log.info(
                "Refund approved",
                kv("refund_id", event.refundId()),
                kv("payment_id", event.paymentId()),
                kv("account_id", event.accountId()));

        outboxWriter.save(
                RefundApprovedIntegrationEventV1.EVENT_TYPE,
                new RefundApprovedIntegrationEventV1(
                        event.refundId(), event.paymentId(), event.accountId(), event.amount()));
    }
}
