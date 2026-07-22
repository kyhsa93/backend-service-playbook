package com.example.accountservice.card.infrastructure.scheduling;

import com.example.accountservice.taskqueue.TaskOutboxWriter;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Once a month, enqueues a task to notify all active cards of last month's usage (monthly card
 * usage statement dispatch, scheduling.md Feature 2). The Scheduler does not execute business logic
 * directly — it only enqueues a task to the Task Queue. The actual statistics calculation and
 * sending are handled by {@code card/interfaces/task/SendCardStatementTaskController} → {@code
 * SendCardStatementService}.
 */
@Component
@RequiredArgsConstructor
public class CardStatementScheduler {

    private static final Logger log = LoggerFactory.getLogger(CardStatementScheduler.class);
    private static final String TASK_TYPE = "card.send-statement";
    private static final String GROUP_ID = "card.statement";

    private final TaskOutboxWriter taskOutboxWriter;

    // With a month-based deduplicationId, even if multiple instances tick simultaneously on the
    // 1st of the same month, only one entry ends up in the Task Queue (scheduling.md "Cron
    // multi-instance safety"). Exceptions are only logged, not rethrown — the next tick (4 AM on
    // the 1st of the following month) will retry.
    @Scheduled(cron = "0 0 4 1 * *") // 4 AM on the 1st of every month
    public void enqueueMonthlyStatement() {
        try {
            YearMonth month = YearMonth.now();
            String dedupId = TASK_TYPE + "-" + month;
            taskOutboxWriter.enqueue(TASK_TYPE, new Payload(month), GROUP_ID, dedupId);
        } catch (Exception e) {
            log.error("Failed to enqueue card statement task", e);
        }
    }

    // The local payload view owned by this Scheduler — contracts only via the JSON schema (field
    // names) with the separate payload record owned by
    // card/interfaces/task/SendCardStatementTaskController (same reasoning as
    // account/infrastructure/scheduling/InterestPaymentScheduler).
    private record Payload(YearMonth month) {}
}
