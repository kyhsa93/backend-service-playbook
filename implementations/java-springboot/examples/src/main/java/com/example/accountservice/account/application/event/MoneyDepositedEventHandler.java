package com.example.accountservice.account.application.event;

import com.example.accountservice.account.application.service.NotificationService;
import com.example.accountservice.account.domain.MoneyDepositedEvent;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Handles the {@link MoneyDepositedEvent} accumulated in the Outbox to send a notification email to
 * the account owner.
 */
@Component
@RequiredArgsConstructor
public class MoneyDepositedEventHandler implements OutboxEventHandler {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return MoneyDepositedEvent.class.getSimpleName();
    }

    @Override
    public void handle(String payload) throws Exception {
        MoneyDepositedEvent event = objectMapper.readValue(payload, MoneyDepositedEvent.class);
        notificationService.sendEmail(
                event.accountId(),
                "MoneyDeposited",
                event.email(),
                "[Account] Your deposit is complete",
                event.amount().amount()
                        + " "
                        + event.amount().currency()
                        + " has been deposited. Balance after deposit: "
                        + event.balanceAfter().amount()
                        + " "
                        + event.balanceAfter().currency());
    }
}
