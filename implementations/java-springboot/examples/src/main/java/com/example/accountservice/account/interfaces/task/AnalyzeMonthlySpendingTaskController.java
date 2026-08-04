package com.example.accountservice.account.interfaces.task;

import com.example.accountservice.account.application.command.AnalyzeMonthlySpendingCommand;
import com.example.accountservice.account.application.command.AnalyzeMonthlySpendingService;
import com.example.accountservice.taskqueue.TaskHandler;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The Task Queue input adapter (Interface layer) — just as an HTTP Controller delegates an HTTP
 * request to an Application Service, this Task Controller receives a Task Queue message and calls
 * {@link AnalyzeMonthlySpendingService} (scheduling.md "Task Controller — the Interface layer"). It
 * only delegates, with no conditional branching or business rules, and lets an exception propagate
 * as-is so {@code TaskConsumer} can decide on retry/DLQ (errors are not swallowed).
 */
@Component
@RequiredArgsConstructor
public class AnalyzeMonthlySpendingTaskController implements TaskHandler {

    private final AnalyzeMonthlySpendingService analyzeMonthlySpendingService;
    private final ObjectMapper objectMapper;

    @Override
    public String taskType() {
        return "account.analyze-monthly-spending";
    }

    @Override
    public void handle(String payload) throws Exception {
        Payload parsed = objectMapper.readValue(payload, Payload.class);
        analyzeMonthlySpendingService.analyze(
                new AnalyzeMonthlySpendingCommand(
                        parsed.analysisMonth(),
                        parsed.monthStart(),
                        parsed.monthEnd(),
                        parsed.previousMonthStart(),
                        parsed.previousMonthEnd()));
    }

    // The local payload view owned by this Task Controller — its field names must match the JSON
    // produced by infrastructure/scheduling/SpendingAnalysisScheduler. It does not share the type,
    // contracting only through the JSON schema, to preserve the layer dependency direction
    // (interfaces does not reference infrastructure).
    private record Payload(
            String analysisMonth,
            LocalDateTime monthStart,
            LocalDateTime monthEnd,
            LocalDateTime previousMonthStart,
            LocalDateTime previousMonthEnd) {}
}
