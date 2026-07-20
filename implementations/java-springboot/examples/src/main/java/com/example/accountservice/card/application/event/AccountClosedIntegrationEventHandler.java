package com.example.accountservice.card.application.event;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.card.application.command.CancelCardsByAccountCommand;
import com.example.accountservice.card.application.command.CancelCardsByAccountService;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 외부 BC(Account)가 발행한 {@code account.closed.v1} Integration Event 수신부. {@code OutboxConsumer}가
 * SQS에서 수신한 메시지를 {@code eventType()} 값으로 자동 라우팅한다.
 */
@Component
@RequiredArgsConstructor
public class AccountClosedIntegrationEventHandler implements OutboxEventHandler {

    private static final Logger log =
            LoggerFactory.getLogger(AccountClosedIntegrationEventHandler.class);

    private final CancelCardsByAccountService cancelCardsByAccountService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return "account.closed.v1";
    }

    @Override
    public void handle(String payload) throws Exception {
        AccountIntegrationEventPayload event =
                objectMapper.readValue(payload, AccountIntegrationEventPayload.class);
        log.info("account.closed.v1 수신", kv("account_id", event.accountId()));
        cancelCardsByAccountService.cancel(new CancelCardsByAccountCommand(event.accountId()));
    }
}
