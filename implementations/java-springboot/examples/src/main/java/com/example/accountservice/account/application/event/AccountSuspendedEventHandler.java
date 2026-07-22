package com.example.accountservice.account.application.event;

import com.example.accountservice.account.application.integrationevent.AccountSuspendedIntegrationEventV1;
import com.example.accountservice.account.application.service.NotificationService;
import com.example.accountservice.account.domain.AccountSuspendedEvent;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.example.accountservice.outbox.OutboxWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles the {@link AccountSuspendedEvent} (an internal Domain Event) accumulated in the Outbox.
 *
 * <p>An EventHandler under application/event/ is the one exception allowed to use {@link
 * OutboxWriter} directly — here it converts the event into the Integration Event ({@code
 * account.suspended.v1}) exposed to external BCs (Card, etc.) and writes it into the same Outbox
 * transaction (an Aggregate never creates an Integration Event directly — the conversion point is
 * always the EventHandler). The newly written {@code account.suspended.v1} row is published to SQS
 * on {@code OutboxPoller}'s next polling tick and received/processed by {@code OutboxConsumer} — it
 * is not processed immediately within this handler's own invocation (it is asynchronous).
 */
@Component
@RequiredArgsConstructor
public class AccountSuspendedEventHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(AccountSuspendedEventHandler.class);

    private final NotificationService notificationService;
    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return AccountSuspendedEvent.class.getSimpleName();
    }

    @Override
    public void handle(String payload) throws Exception {
        AccountSuspendedEvent event = objectMapper.readValue(payload, AccountSuspendedEvent.class);

        // Write the Integration Event notifying external BCs (Card, etc.) into the Outbox.
        outboxWriter.save(
                AccountSuspendedIntegrationEventV1.EVENT_TYPE,
                new AccountSuspendedIntegrationEventV1(event.accountId(), event.suspendedAt()));

        // The notification is best-effort. Even on failure, this method does not end by throwing —
        // throwing would cause this outbox row to be re-drained, which would write the Integration
        // Event above again (harmless since the receiver is idempotent, but this avoids unnecessary
        // amplification). Retrying the notification itself is handled by a separate outbox row.
        try {
            notificationService.sendEmail(
                    event.accountId(),
                    "AccountSuspended",
                    event.email(),
                    "[Account] Your account has been suspended",
                    "Account (" + event.accountId() + ") has been suspended.");
        } catch (Exception e) {
            log.error("Failed to send suspension notification", e);
        }
    }
}
