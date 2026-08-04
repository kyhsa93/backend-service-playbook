package com.example.accountservice.account.interfaces.task;

import com.example.accountservice.account.application.command.ForecastSpendingCommand;
import com.example.accountservice.account.application.command.ForecastSpendingService;
import com.example.accountservice.taskqueue.TaskHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The Task Queue input adapter (Interface layer) — just as an HTTP Controller delegates an HTTP
 * request to an Application Service, this Task Controller receives a Task Queue message and calls
 * {@link ForecastSpendingService} (scheduling.md "Task Controller — the Interface layer"). It only
 * delegates, with no conditional branching or business rules, and lets an exception propagate as-is
 * so {@code TaskConsumer} can decide on retry/DLQ (errors are not swallowed).
 */
@Component
@RequiredArgsConstructor
public class ForecastSpendingTaskController implements TaskHandler {

    private final ForecastSpendingService forecastSpendingService;
    private final ObjectMapper objectMapper;

    @Override
    public String taskType() {
        return "account.forecast-spending";
    }

    @Override
    public void handle(String payload) throws Exception {
        Payload parsed = objectMapper.readValue(payload, Payload.class);
        forecastSpendingService.forecast(new ForecastSpendingCommand(parsed.forecastMonth()));
    }

    // The local payload view owned by this Task Controller — its field names must match the JSON
    // produced by infrastructure/scheduling/SpendingForecastScheduler. It does not share the type,
    // contracting only through the JSON schema, to preserve the layer dependency direction
    // (interfaces does not reference infrastructure).
    private record Payload(String forecastMonth) {}
}
