package com.example.accountservice.card.interfaces.task;

import com.example.accountservice.card.application.command.SendCardStatementCommand;
import com.example.accountservice.card.application.command.SendCardStatementService;
import com.example.accountservice.taskqueue.TaskHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A Task Queue input adapter (Interface layer) — just as an HTTP Controller delegates HTTP requests
 * to an Application Service, this Task Controller receives a Task Queue message and invokes {@link
 * SendCardStatementService} (scheduling.md "Task Controller — Interface layer"). It only delegates,
 * without any conditional branching or business rules, and lets exceptions propagate as-is so
 * {@code TaskConsumer} can decide whether to retry or send to the DLQ.
 */
@Component
@RequiredArgsConstructor
public class SendCardStatementTaskController implements TaskHandler {

    private final SendCardStatementService sendCardStatementService;
    private final ObjectMapper objectMapper;

    @Override
    public String taskType() {
        return "card.send-statement";
    }

    @Override
    public void handle(String payload) throws Exception {
        Payload parsed = objectMapper.readValue(payload, Payload.class);
        sendCardStatementService.sendStatements(new SendCardStatementCommand(parsed.month()));
    }

    // The local payload view owned by this Task Controller — its field names must match the
    // JSON produced by infrastructure/scheduling/CardStatementScheduler.
    private record Payload(YearMonth month) {}
}
