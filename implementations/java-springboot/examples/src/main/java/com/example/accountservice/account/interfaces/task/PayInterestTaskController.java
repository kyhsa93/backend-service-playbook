package com.example.accountservice.account.interfaces.task;

import com.example.accountservice.account.application.command.PayInterestCommand;
import com.example.accountservice.account.application.command.PayInterestService;
import com.example.accountservice.taskqueue.TaskHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The Task Queue input adapter (Interface layer) — just as an HTTP Controller delegates an HTTP
 * request to an Application Service, this Task Controller receives a Task Queue message and calls
 * {@link PayInterestService} (scheduling.md "Task Controller — the Interface layer"). It only
 * delegates, with no conditional branching or business rules, and lets an exception propagate as-is
 * so {@code TaskConsumer} can decide on retry/DLQ (errors are not swallowed).
 */
@Component
@RequiredArgsConstructor
public class PayInterestTaskController implements TaskHandler {

    private final PayInterestService payInterestService;
    private final ObjectMapper objectMapper;

    @Override
    public String taskType() {
        return "account.pay-interest";
    }

    @Override
    public void handle(String payload) throws Exception {
        Payload parsed = objectMapper.readValue(payload, Payload.class);
        payInterestService.payInterest(new PayInterestCommand(parsed.date()));
    }

    // The local payload view owned by this Task Controller — its field names must match the JSON
    // produced by infrastructure/scheduling/InterestPaymentScheduler. It does not share the type,
    // contracting only through the JSON schema, to preserve the layer dependency direction
    // (interfaces
    // does not reference infrastructure).
    private record Payload(LocalDate date) {}
}
