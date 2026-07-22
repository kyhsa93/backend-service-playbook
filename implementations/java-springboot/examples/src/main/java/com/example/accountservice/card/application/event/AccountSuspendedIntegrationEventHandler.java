package com.example.accountservice.card.application.event;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.card.application.command.SuspendCardsByAccountCommand;
import com.example.accountservice.card.application.command.SuspendCardsByAccountService;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Receiver for the {@code account.suspended.v1} Integration Event published by an external BC
 * (Account). Implementing {@link OutboxEventHandler} lets {@code OutboxConsumer} automatically
 * route messages received from SQS based on the {@code eventType()} value, so the connection works
 * even though the Account BC has no knowledge of the Card BC. It only invokes its own domain's use
 * case (Command), and on failure lets the exception propagate so the message is not deleted — it
 * will be redelivered and retried after the SQS visibility timeout.
 */
@Component
@RequiredArgsConstructor
public class AccountSuspendedIntegrationEventHandler implements OutboxEventHandler {

    private static final Logger log =
            LoggerFactory.getLogger(AccountSuspendedIntegrationEventHandler.class);

    private final SuspendCardsByAccountService suspendCardsByAccountService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return "account.suspended.v1";
    }

    @Override
    public void handle(String payload) throws Exception {
        AccountIntegrationEventPayload event =
                objectMapper.readValue(payload, AccountIntegrationEventPayload.class);
        log.info("account.suspended.v1 received", kv("account_id", event.accountId()));
        suspendCardsByAccountService.suspend(new SuspendCardsByAccountCommand(event.accountId()));
    }
}
