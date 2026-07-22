package com.example.accountservice.card.application.event;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.card.application.command.CancelCardsByAccountCommand;
import com.example.accountservice.card.application.command.CancelCardsByAccountService;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Receiver for the {@code account.closed.v1} Integration Event published by an external BC
 * (Account). {@code OutboxConsumer} automatically routes messages received from SQS based on the
 * {@code eventType()} value.
 */
@Component
@RequiredArgsConstructor
public class AccountClosedIntegrationEventHandler implements OutboxEventHandler {

    private static final Logger log =
            LoggerFactory.getLogger(AccountClosedIntegrationEventHandler.class);

    private final CancelCardsByAccountService cancelCardsByAccountService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return "account.closed.v1";
    }

    @Override
    public void handle(String payload) throws Exception {
        AccountIntegrationEventPayload event =
                objectMapper.readValue(payload, AccountIntegrationEventPayload.class);
        log.info("account.closed.v1 received", kv("account_id", event.accountId()));
        cancelCardsByAccountService.cancel(new CancelCardsByAccountCommand(event.accountId()));
    }
}
