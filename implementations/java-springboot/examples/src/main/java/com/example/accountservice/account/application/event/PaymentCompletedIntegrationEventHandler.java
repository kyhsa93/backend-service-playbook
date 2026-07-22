package com.example.accountservice.account.application.event;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.account.application.command.WithdrawByPaymentCommand;
import com.example.accountservice.account.application.command.WithdrawByPaymentService;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The receiver for the {@code payment.completed.v1} Integration Event published by the external BC
 * (Payment). Implementing {@link OutboxEventHandler} means {@code OutboxConsumer} automatically
 * routes messages received from SQS by their {@code eventType()} value, so the connection works
 * even though the Payment BC has no knowledge of the Account BC (the same structure as
 * card/application/event/AccountSuspendedIntegrationEventHandler.java). It calls only its own
 * domain's use case (Command), and on failure lets the exception propagate as-is so the message is
 * not deleted — it is redelivered and retried after the SQS visibility timeout.
 */
@Component
@RequiredArgsConstructor
public class PaymentCompletedIntegrationEventHandler implements OutboxEventHandler {

    private static final Logger log =
            LoggerFactory.getLogger(PaymentCompletedIntegrationEventHandler.class);

    private final WithdrawByPaymentService withdrawByPaymentService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return "payment.completed.v1";
    }

    @Override
    public void handle(String payload) throws Exception {
        PaymentIntegrationEventPayload event =
                objectMapper.readValue(payload, PaymentIntegrationEventPayload.class);
        log.info(
                "payment.completed.v1 received",
                kv("payment_id", event.paymentId()),
                kv("account_id", event.accountId()));
        withdrawByPaymentService.withdraw(
                new WithdrawByPaymentCommand(event.accountId(), event.amount(), event.paymentId()));
    }
}
