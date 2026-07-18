package com.example.accountservice.payment.application.event;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.outbox.OutboxEventHandler;
import com.example.accountservice.outbox.OutboxWriter;
import com.example.accountservice.payment.application.integrationevent.PaymentCancelledIntegrationEventV1;
import com.example.accountservice.payment.domain.PaymentCancelledEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Outbox에 쌓인 {@link PaymentCancelledEvent}를 처리해 {@code payment.cancelled.v1} Integration Event로 변환해
 * 적재한다. Account BC가 이를 구독해 보상 크레딧(deposit)을 실행한다 — 이미 차감된 금액을 되돌리는 보상 트랜잭션이다.
 */
@Component
@RequiredArgsConstructor
public class PaymentCancelledEventHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentCancelledEventHandler.class);

    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return PaymentCancelledEvent.class.getSimpleName();
    }

    @Override
    public void handle(String payload) throws Exception {
        PaymentCancelledEvent event = objectMapper.readValue(payload, PaymentCancelledEvent.class);
        log.info(
                "결제 취소됨",
                kv("payment_id", event.paymentId()),
                kv("account_id", event.accountId()),
                kv("reason", event.reason()));

        outboxWriter.save(
                PaymentCancelledIntegrationEventV1.EVENT_TYPE,
                new PaymentCancelledIntegrationEventV1(
                        event.paymentId(), event.accountId(), event.amount()));
    }
}
