package com.example.accountservice.account.application.event;

import com.example.accountservice.account.application.service.NotificationService;
import com.example.accountservice.account.domain.AccountCreatedEvent;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Handles the {@link AccountCreatedEvent} accumulated in the Outbox to send a notification email to
 * the account owner.
 */
@Component
@RequiredArgsConstructor
public class AccountCreatedEventHandler implements OutboxEventHandler {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return AccountCreatedEvent.class.getSimpleName();
    }

    @Override
    public void handle(String payload) throws Exception {
        AccountCreatedEvent event = objectMapper.readValue(payload, AccountCreatedEvent.class);
        notificationService.sendEmail(
                event.accountId(),
                "AccountCreated",
                event.email(),
                "[Account] Your account has been opened",
                "Account ("
                        + event.accountId()
                        + ") has been opened. Currency: "
                        + event.currency());
    }
}
