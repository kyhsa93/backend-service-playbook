package com.example.accountservice.account.application.event;

import com.example.accountservice.account.domain.AccountReactivatedEvent;
import com.example.accountservice.account.application.service.NotificationService;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Outbox에 쌓인 {@link AccountReactivatedEvent}를 처리해 계좌 소유자에게 알림 이메일을 발송한다.
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
        AccountReactivatedEvent event = objectMapper.readValue(payload, AccountReactivatedEvent.class);
        notificationService.sendEmail(event.accountId(), "AccountReactivated", event.email(),
                "[Account] 계좌가 재개되었습니다",
                "계좌(" + event.accountId() + ")가 재개되었습니다.");
    }
}
