package com.example.accountservice.account.application.event;

import com.example.accountservice.account.application.service.NotificationService;
import com.example.accountservice.account.domain.MoneyWithdrawnEvent;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Handles the {@link MoneyWithdrawnEvent} accumulated in the Outbox to send a notification email to
 * the account owner.
 */
@Component
@RequiredArgsConstructor
public class MoneyWithdrawnEventHandler implements OutboxEventHandler {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return MoneyWithdrawnEvent.class.getSimpleName();
    }

    @Override
    public void handle(String payload) throws Exception {
        MoneyWithdrawnEvent event = objectMapper.readValue(payload, MoneyWithdrawnEvent.class);
        notificationService.sendEmail(
                event.accountId(),
                "MoneyWithdrawn",
                event.email(),
                "[Account] Your withdrawal is complete",
                event.amount().amount()
                        + " "
                        + event.amount().currency()
                        + " has been withdrawn. Balance after withdrawal: "
                        + event.balanceAfter().amount()
                        + " "
                        + event.balanceAfter().currency());
    }
}
