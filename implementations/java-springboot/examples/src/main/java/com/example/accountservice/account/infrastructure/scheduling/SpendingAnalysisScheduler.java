package com.example.accountservice.account.infrastructure.scheduling;

import com.example.accountservice.account.infrastructure.PreviousSpendingAnalysisPeriod;
import com.example.accountservice.common.UtcClock;
import com.example.accountservice.taskqueue.TaskOutboxWriter;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Enqueues the monthly spending-analysis ETL batch once a month. The Scheduler does not execute
 * business logic directly, only enqueues it to the Task Queue — the actual summarization/%-change
 * computation is handled by {@code account/interfaces/task/AnalyzeMonthlySpendingTaskController} →
 * {@code AnalyzeMonthlySpendingService}. Mirrors {@code InterestPaymentScheduler}'s exact wiring
 * pattern.
 */
@Component
@RequiredArgsConstructor
public class SpendingAnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(SpendingAnalysisScheduler.class);
    private static final String TASK_TYPE = "account.analyze-monthly-spending";
    private static final String GROUP_ID = "account.spending-analysis";

    private final TaskOutboxWriter taskOutboxWriter;

    // The 1st of every month at 2 AM — an hour before the card-statement job (4 AM), so the two
    // batch jobs don't contend for the database at the exact same moment. Thanks to the
    // month-based deduplicationId, only one entry lands in the Task Queue even if multiple
    // instances tick on the same day simultaneously (scheduling.md "Cron multi-instance safety").
    // An exception is explicitly logged only, not rethrown — it is retried on the next tick (2 AM
    // on the 1st of the following month).
    @Scheduled(cron = "0 0 2 1 * *")
    public void enqueueMonthlySpendingAnalysis() {
        PreviousSpendingAnalysisPeriod period =
                PreviousSpendingAnalysisPeriod.compute(UtcClock.currentMonth());
        String dedupId = TASK_TYPE + "-" + period.analysisMonth();
        try {
            taskOutboxWriter.enqueue(
                    TASK_TYPE,
                    new Payload(
                            period.analysisMonth(),
                            period.monthStart(),
                            period.monthEnd(),
                            period.previousMonthStart(),
                            period.previousMonthEnd()),
                    GROUP_ID,
                    dedupId);
        } catch (Exception e) {
            log.error("Failed to enqueue the monthly spending analysis Task", e);
        }
    }

    // The local payload view owned by this Scheduler — it contracts only through the JSON schema
    // (field names) with the separate payload record owned by
    // account/interfaces/task/AnalyzeMonthlySpendingTaskController. This is so Infrastructure does
    // not directly reference a type from the Interfaces layer (preserving the layer dependency
    // direction).
    private record Payload(
            String analysisMonth,
            LocalDateTime monthStart,
            LocalDateTime monthEnd,
            LocalDateTime previousMonthStart,
            LocalDateTime previousMonthEnd) {}
}
