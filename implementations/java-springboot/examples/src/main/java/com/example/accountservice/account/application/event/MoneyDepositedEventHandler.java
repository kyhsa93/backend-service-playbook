package com.example.accountservice.account.application.event;

import com.example.accountservice.account.domain.MoneyDepositedEvent;
import com.example.accountservice.notification.application.service.NotificationService;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Outbox에 쌓인 {@link MoneyDepositedEvent}를 처리해 계좌 소유자에게 알림 이메일을 발송한다.
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
        notificationService.sendEmail(event.accountId(), "MoneyDeposited", event.email(),
                "[Account] 입금이 완료되었습니다",
                event.amount().amount() + " " + event.amount().currency() + "이 입금되었습니다. 입금 후 잔액: "
                        + event.balanceAfter().amount() + " " + event.balanceAfter().currency());
    }
}
