package com.example.accountservice.account.application.event;

import com.example.accountservice.account.domain.AccountSuspendedEvent;
import com.example.accountservice.notification.application.service.NotificationService;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Outbox에 쌓인 {@link AccountSuspendedEvent}를 처리해 계좌 소유자에게 알림 이메일을 발송한다.
 */
@Component
@RequiredArgsConstructor
public class AccountSuspendedEventHandler implements OutboxEventHandler {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return AccountSuspendedEvent.class.getSimpleName();
    }

    @Override
    public void handle(String payload) throws Exception {
        AccountSuspendedEvent event = objectMapper.readValue(payload, AccountSuspendedEvent.class);
        notificationService.sendEmail(event.accountId(), "AccountSuspended", event.email(),
                "[Account] 계좌가 정지되었습니다",
                "계좌(" + event.accountId() + ")가 정지되었습니다.");
    }
}
