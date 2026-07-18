package com.example.accountservice.payment.application.event;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.outbox.OutboxEventHandler;
import com.example.accountservice.outbox.OutboxWriter;
import com.example.accountservice.payment.application.integrationevent.PaymentCompletedIntegrationEventV1;
import com.example.accountservice.payment.domain.PaymentCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Outbox에 쌓인 {@link PaymentCompletedEvent}(내부 Domain Event)를 처리해 외부 BC(Account)에 공개하는 Integration
 * Event({@code payment.completed.v1})로 변환해 같은 Outbox 트랜잭션에 적재한다. Account BC의 {@code
 * PaymentCompletedIntegrationEventHandler}가 이 eventType으로 자동 라우팅되어 실제 차감(withdraw)을 수행한다 —
 * account/application/event/AccountSuspendedEventHandler.java와 동일한 변환 지점 패턴이다.
 */
@Component
@RequiredArgsConstructor
public class PaymentCompletedEventHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentCompletedEventHandler.class);

    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return PaymentCompletedEvent.class.getSimpleName();
    }

    @Override
    public void handle(String payload) throws Exception {
        PaymentCompletedEvent event = objectMapper.readValue(payload, PaymentCompletedEvent.class);
        log.info(
                "결제 완료됨",
                kv("payment_id", event.paymentId()),
                kv("account_id", event.accountId()),
                kv("amount", event.amount()));

        outboxWriter.save(
                PaymentCompletedIntegrationEventV1.EVENT_TYPE,
                new PaymentCompletedIntegrationEventV1(
                        event.paymentId(), event.accountId(), event.amount()));
    }
}
