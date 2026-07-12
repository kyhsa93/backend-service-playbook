package com.example.accountservice.card.application.event;

import com.example.accountservice.card.application.command.SuspendCardsByAccountCommand;
import com.example.accountservice.card.application.command.SuspendCardsByAccountService;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * 외부 BC(Account)가 발행한 {@code account.suspended.v1} Integration Event 수신부.
 * {@link OutboxEventHandler}로 구현하면 {@link com.example.accountservice.outbox.OutboxRelay}가
 * {@code eventType()} 값으로 자동 라우팅하므로, Account BC가 Card BC를 몰라도 연결된다.
 * 자기 도메인의 유스케이스(Command)만 호출하고, 실패 시 예외를 그대로 던져 Relay가 재시도하게 한다.
 */
@Component
@RequiredArgsConstructor
public class AccountSuspendedIntegrationEventHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(AccountSuspendedIntegrationEventHandler.class);

    private final SuspendCardsByAccountService suspendCardsByAccountService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return "account.suspended.v1";
    }

    @Override
    public void handle(String payload) throws Exception {
        AccountIntegrationEventPayload event = objectMapper.readValue(payload, AccountIntegrationEventPayload.class);
        log.info("account.suspended.v1 수신", kv("account_id", event.accountId()));
        suspendCardsByAccountService.suspend(new SuspendCardsByAccountCommand(event.accountId()));
    }
}
