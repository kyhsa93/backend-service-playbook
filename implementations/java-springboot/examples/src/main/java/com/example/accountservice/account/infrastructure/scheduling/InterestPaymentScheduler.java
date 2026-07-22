package com.example.accountservice.account.infrastructure.scheduling;

import com.example.accountservice.taskqueue.TaskOutboxWriter;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Enqueues the batch that pays interest to all active accounts once daily (scheduled interest
 * payment, scheduling.md Feature 1). The Scheduler does not execute business logic directly, only
 * enqueues it to the Task Queue — the actual interest calculation/payment is handled by {@code
 * account/interfaces/task/PayInterestTaskController} → {@code PayInterestService}.
 */
@Component
@RequiredArgsConstructor
public class InterestPaymentScheduler {

    private static final Logger log = LoggerFactory.getLogger(InterestPaymentScheduler.class);
    private static final String TASK_TYPE = "account.pay-interest";
    private static final String GROUP_ID = "account.interest";

    private final TaskOutboxWriter taskOutboxWriter;

    // Thanks to the date-based deduplicationId, only one entry lands in the Task Queue even if
    // multiple instances tick on the same day simultaneously (scheduling.md "Cron multi-instance
    // safety"). An exception is explicitly logged only, not rethrown — it is retried on the next
    // tick
    // (3 AM the following day).
    @Scheduled(cron = "0 0 3 * * *") // Every day at 3 AM
    public void enqueueDailyInterestPayment() {
        try {
            LocalDate today = LocalDate.now();
            String dedupId = TASK_TYPE + "-" + today;
            taskOutboxWriter.enqueue(TASK_TYPE, new Payload(today), GROUP_ID, dedupId);
        } catch (Exception e) {
            log.error("Failed to enqueue the interest-payment Task", e);
        }
    }

    // The local payload view owned by this Scheduler — it contracts only through the JSON schema
    // (field names) with the separate payload record owned by
    // account/interfaces/task/PayInterestTaskController. This is so Infrastructure does not
    // directly
    // reference a type from the Interfaces layer (preserving the layer dependency direction).
    private record Payload(LocalDate date) {}
}
