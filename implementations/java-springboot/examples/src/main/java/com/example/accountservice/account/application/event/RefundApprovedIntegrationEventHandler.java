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
 * The receiver for the {@code refund.approved.v1} Integration Event published by the external BC
 * (Payment) — it performs the refund credit (deposit).
 */
@Component
@RequiredArgsConstructor
public class RefundApprovedIntegrationEventHandler implements OutboxEventHandler {

    private static final Logger log =
            LoggerFactory.getLogger(RefundApprovedIntegrationEventHandler.class);

    private final DepositByPaymentService depositByPaymentService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return "refund.approved.v1";
    }

    @Override
    public void handle(String payload) throws Exception {
        PaymentIntegrationEventPayload event =
                objectMapper.readValue(payload, PaymentIntegrationEventPayload.class);
        log.info(
                "refund.approved.v1 received",
                kv("refund_id", event.refundId()),
                kv("account_id", event.accountId()));
        depositByPaymentService.deposit(
                new DepositByPaymentCommand(event.accountId(), event.amount(), event.refundId()));
    }
}
