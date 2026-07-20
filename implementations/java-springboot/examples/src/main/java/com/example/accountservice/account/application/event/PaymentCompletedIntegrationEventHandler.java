package com.example.accountservice.account.application.event;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.account.application.command.WithdrawByPaymentCommand;
import com.example.accountservice.account.application.command.WithdrawByPaymentService;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 외부 BC(Payment)가 발행한 {@code payment.completed.v1} Integration Event 수신부. {@link
 * OutboxEventHandler}로 구현하면 {@code OutboxConsumer}가 SQS에서 수신한 메시지를 {@code eventType()} 값으로 자동
 * 라우팅하므로, Payment BC가 Account BC를 몰라도 연결된다(card/application/event/
 * AccountSuspendedIntegrationEventHandler.java와 동일한 구조). 자기 도메인의 유스케이스(Command)만 호출하고, 실패 시 예외를 그대로
 * 던져 메시지를 삭제하지 않게 한다 — SQS visibility timeout 이후 재수신되어 재시도된다.
 */
@Component
@RequiredArgsConstructor
public class PaymentCompletedIntegrationEventHandler implements OutboxEventHandler {

    private static final Logger log =
            LoggerFactory.getLogger(PaymentCompletedIntegrationEventHandler.class);

    private final WithdrawByPaymentService withdrawByPaymentService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return "payment.completed.v1";
    }

    @Override
    public void handle(String payload) throws Exception {
        PaymentIntegrationEventPayload event =
                objectMapper.readValue(payload, PaymentIntegrationEventPayload.class);
        log.info(
                "payment.completed.v1 수신",
                kv("payment_id", event.paymentId()),
                kv("account_id", event.accountId()));
        withdrawByPaymentService.withdraw(
                new WithdrawByPaymentCommand(event.accountId(), event.amount(), event.paymentId()));
    }
}
