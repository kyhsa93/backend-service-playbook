package com.example.accountservice.account.application.event;

import com.example.accountservice.account.application.integrationevent.AccountClosedIntegrationEventV1;
import com.example.accountservice.account.domain.AccountClosedEvent;
import com.example.accountservice.account.application.service.NotificationService;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.example.accountservice.outbox.OutboxWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Outbox에 쌓인 {@link AccountClosedEvent}(내부 Domain Event)를 처리한다.
 * 외부 BC용 Integration Event({@code account.closed.v1})를 같은 Outbox 트랜잭션에 적재한다
 * (AccountSuspendedEventHandler와 동일한 이유·구조).
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

        // 외부 BC용 Integration Event(account.closed.v1)를 Outbox에 적재한다.
        outboxWriter.save(AccountClosedIntegrationEventV1.EVENT_TYPE,
                new AccountClosedIntegrationEventV1(event.accountId(), event.closedAt()));

        // 알림은 best-effort다(정지 핸들러와 동일한 이유 — Integration Event 중복 적재 방지).
        try {
            notificationService.sendEmail(event.accountId(), "AccountClosed", event.email(),
                    "[Account] 계좌가 종료되었습니다",
                    "계좌(" + event.accountId() + ")가 종료되었습니다.");
        } catch (Exception e) {
            log.error("종료 알림 발송 실패", e);
        }
    }
}
