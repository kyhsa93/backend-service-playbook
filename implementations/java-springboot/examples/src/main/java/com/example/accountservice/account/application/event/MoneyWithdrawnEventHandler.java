package com.example.accountservice.account.application.event;

import com.example.accountservice.account.domain.MoneyWithdrawnEvent;
import com.example.accountservice.account.application.service.NotificationService;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Outbox에 쌓인 {@link MoneyWithdrawnEvent}를 처리해 계좌 소유자에게 알림 이메일을 발송한다.
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
        notificationService.sendEmail(event.accountId(), "MoneyWithdrawn", event.email(),
                "[Account] 출금이 완료되었습니다",
                event.amount().amount() + " " + event.amount().currency() + "이 출금되었습니다. 출금 후 잔액: "
                        + event.balanceAfter().amount() + " " + event.balanceAfter().currency());
    }
}
