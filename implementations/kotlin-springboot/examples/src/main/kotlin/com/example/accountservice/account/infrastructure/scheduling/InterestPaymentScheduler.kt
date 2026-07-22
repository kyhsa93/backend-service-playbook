package com.example.accountservice.account.infrastructure.scheduling

import com.example.accountservice.taskqueue.TaskQueue
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * The recurring interest-payment Scheduler — placed in the Infrastructure layer (scheduling.md, "A
 * Scheduler is placed in the Infrastructure layer"). It does not execute business logic directly; it
 * only enqueues a Task onto the [TaskQueue] — the actual interest calculation/crediting is handled by
 * `PayInterestService`, via [com.example.accountservice.account.interfaces.task.PayInterestTaskController],
 * which receives the `account.pay-interest` Task.
 *
 * [payDate] is fixed at enqueue time and passed as the payload — even if Task processing (the Consumer)
 * is delayed (e.g. around midnight), "which date's interest this is" is not recalculated and stays
 * exactly what it was at enqueue time. This same value is used as the idempotency key for
 * `Account.payInterest(payDate)` (compared against
 * [com.example.accountservice.account.domain.Account.lastInterestPaidAt]).
 */
@Component
class InterestPaymentScheduler(
    private val taskQueue: TaskQueue,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(InterestPaymentScheduler::class.java)

    @Scheduled(cron = "0 0 3 * * *") // every day at 03:00
    fun enqueueDailyInterestPayment() {
        val payDate = LocalDate.now()
        val dedupId = "$TASK_TYPE-$payDate"
        // Exceptions from the Cron handler are caught explicitly and logged so the scheduling framework
        // does not silently swallow them (scheduling.md, "Cron exceptions are logged explicitly"). If
        // the enqueue itself fails (e.g. a DB outage), today's Task is lost — the next tick enqueues
        // with tomorrow's date, so today is not automatically retried. Within this repository's scope,
        // monitoring this log (ERROR level) with an alert and re-running manually when needed is
        // considered sufficient.
        runCatching {
            taskQueue.enqueue(
                taskType = TASK_TYPE,
                payload = objectMapper.writeValueAsString(mapOf("date" to payDate.toString())),
                groupId = TASK_TYPE,
                deduplicationId = dedupId,
            )
        }.onFailure {
            logger
                .atError()
                .addKeyValue("pay_date", payDate.toString())
                .setCause(it)
                .log("Failed to enqueue the daily interest-payment Task")
        }
    }

    companion object {
        const val TASK_TYPE = "account.pay-interest"
    }
}
