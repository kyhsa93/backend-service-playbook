package com.example.accountservice.account.application.event;

import com.example.accountservice.account.application.service.NotificationService;
import com.example.accountservice.account.domain.AccountReactivatedEvent;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Handles the {@link AccountReactivatedEvent} accumulated in the Outbox to send a notification
 * email to the account owner.
 */
@Component
@RequiredArgsConstructor
public class AccountReactivatedEventHandler implements OutboxEventHandler {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return AccountReactivatedEvent.class.getSimpleName();
    }

    @Override
    public void handle(String payload) throws Exception {
        AccountReactivatedEvent event =
                objectMapper.readValue(payload, AccountReactivatedEvent.class);
        notificationService.sendEmail(
                event.accountId(),
                "AccountReactivated",
                event.email(),
                "[Account] Your account has been reactivated",
                "Account (" + event.accountId() + ") has been reactivated.");
    }
}
