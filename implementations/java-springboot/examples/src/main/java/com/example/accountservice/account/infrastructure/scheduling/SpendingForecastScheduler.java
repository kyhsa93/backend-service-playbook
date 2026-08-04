package com.example.accountservice.account.infrastructure.scheduling;

import com.example.accountservice.common.UtcClock;
import com.example.accountservice.taskqueue.TaskOutboxWriter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Enqueues the monthly spending-forecast training batch once a month. The Scheduler does not
 * execute business logic directly, only enqueues it to the Task Queue — the actual training/
 * prediction is handled by {@code account/interfaces/task/ForecastSpendingTaskController} → {@code
 * ForecastSpendingService}. Mirrors {@code SpendingAnalysisScheduler}'s exact wiring pattern.
 */
@Component
@RequiredArgsConstructor
public class SpendingForecastScheduler {

    private static final Logger log = LoggerFactory.getLogger(SpendingForecastScheduler.class);
    private static final String TASK_TYPE = "account.forecast-spending";
    private static final String GROUP_ID = "account.spending-forecast";

    private final TaskOutboxWriter taskOutboxWriter;

    // The 1st of every month at 3 AM — an hour after account.analyze-monthly-spending (2 AM), so
    // this month's history (last month's freshly-written analysis row) is guaranteed to exist
    // before training reads it. Thanks to the month-based deduplicationId, only one entry lands in
    // the Task Queue even if multiple instances tick on the same day simultaneously
    // (scheduling.md "Cron multi-instance safety"). An exception is explicitly logged only, not
    // rethrown — it is retried on the next tick (3 AM on the 1st of the following month).
    @Scheduled(cron = "0 0 3 1 * *")
    public void enqueueMonthlySpendingForecast() {
        String forecastMonth = UtcClock.currentMonth().toString();
        String dedupId = TASK_TYPE + "-" + forecastMonth;
        try {
            taskOutboxWriter.enqueue(TASK_TYPE, new Payload(forecastMonth), GROUP_ID, dedupId);
        } catch (Exception e) {
            log.error("Failed to enqueue the monthly spending forecast Task", e);
        }
    }

    // The local payload view owned by this Scheduler — it contracts only through the JSON schema
    // (field names) with the separate payload record owned by
    // account/interfaces/task/ForecastSpendingTaskController. This is so Infrastructure does not
    // directly reference a type from the Interfaces layer (preserving the layer dependency
    // direction).
    private record Payload(String forecastMonth) {}
}
