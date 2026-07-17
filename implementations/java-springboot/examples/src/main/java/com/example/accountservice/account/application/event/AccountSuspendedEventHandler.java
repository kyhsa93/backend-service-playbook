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
 * Outbox에 쌓인 {@link AccountSuspendedEvent}(내부 Domain Event)를 처리한다.
 *
 * <p>application/event/의 EventHandler는 {@link OutboxWriter}를 직접 사용할 수 있는 유일한 예외다 — 여기서 외부 BC(Card
 * 등)에 공개하는 Integration Event({@code account.suspended.v1})로 변환해 같은 Outbox 트랜잭션에 적재한다(Aggregate가
 * Integration Event를 직접 만들지 않는다 — 변환 지점은 항상 EventHandler다). {@link
 * com.example.accountservice.outbox.OutboxRelay}가 이 핸들러를 실행하는 같은 {@code processPending()} 호출 안에서 여러
 * 패스로 드레인하므로, 새로 적재된 {@code account.suspended.v1}도 이어서 처리된다.
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

        // 외부 BC(Card 등)에 알리는 Integration Event를 Outbox에 적재한다.
        outboxWriter.save(
                AccountSuspendedIntegrationEventV1.EVENT_TYPE,
                new AccountSuspendedIntegrationEventV1(event.accountId(), event.suspendedAt()));

        // 알림은 best-effort다. 실패해도 이 메서드를 throw로 끝내지 않는다 — throw하면 이 outbox
        // 행이 재드레인되어 위 Integration Event가 중복 적재되기 때문이다(수신 측이 멱등이라
        // 무해하지만 불필요한 증폭을 피한다). 알림 자체의 재시도는 별도 outbox 행의 몫이다.
        try {
            notificationService.sendEmail(
                    event.accountId(),
                    "AccountSuspended",
                    event.email(),
                    "[Account] 계좌가 정지되었습니다",
                    "계좌(" + event.accountId() + ")가 정지되었습니다.");
        } catch (Exception e) {
            log.error("정지 알림 발송 실패", e);
        }
    }
}
