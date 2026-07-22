package com.example.accountservice.account.application.event;

import com.example.accountservice.account.application.integrationevent.AccountClosedIntegrationEventV1;
import com.example.accountservice.account.application.service.NotificationService;
import com.example.accountservice.account.domain.AccountClosedEvent;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.example.accountservice.outbox.OutboxWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles the {@link AccountClosedEvent} (an internal Domain Event) accumulated in the Outbox. It
 * writes the Integration Event for external BCs ({@code account.closed.v1}) into the same Outbox
 * transaction (same reasoning/structure as AccountSuspendedEventHandler).
 */
@Component
@RequiredArgsConstructor
public class AccountClosedEventHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(AccountClosedEventHandler.class);

    private final NotificationService notificationService;
    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return AccountClosedEvent.class.getSimpleName();
    }

    @Override
    public void handle(String payload) throws Exception {
        AccountClosedEvent event = objectMapper.readValue(payload, AccountClosedEvent.class);

        // Write the Integration Event for external BCs (account.closed.v1) into the Outbox.
        outboxWriter.save(
                AccountClosedIntegrationEventV1.EVENT_TYPE,
                new AccountClosedIntegrationEventV1(event.accountId(), event.closedAt()));

        // The notification is best-effort (same reason as the suspend handler — avoid duplicate
        // Integration Event writes).
        try {
            notificationService.sendEmail(
                    event.accountId(),
                    "AccountClosed",
                    event.email(),
                    "[Account] Your account has been closed",
                    "Account (" + event.accountId() + ") has been closed.");
        } catch (Exception e) {
            log.error("Failed to send closure notification", e);
        }
    }
}
