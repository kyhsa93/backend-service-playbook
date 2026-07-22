package com.example.accountservice.card.infrastructure.scheduling

import com.example.accountservice.taskqueue.TaskQueue
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.YearMonth

/**
 * The Scheduler for monthly card-usage-statement delivery — Infrastructure layer (the same
 * role/structure as
 * [com.example.accountservice.account.infrastructure.scheduling.InterestPaymentScheduler]). It only
 * enqueues the Task onto the [TaskQueue]; the actual aggregation/delivery is handled by
 * [com.example.accountservice.card.interfaces.task.SendCardStatementTaskController] →
 * `SendMonthlyCardStatementsService`, which receive the `card.send-statement` Task.
 *
 * [yearMonth] (the current month at enqueue time) is used only as the duplicate-send-prevention key —
 * the actual aggregation period (the last 30 days) is calculated separately by
 * `SendMonthlyCardStatementsService` based on when the Task is processed (see that class's KDoc).
 */
@Component
class CardStatementScheduler(
    private val taskQueue: TaskQueue,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(CardStatementScheduler::class.java)

    @Scheduled(cron = "0 0 4 1 * *") // 04:00 on the 1st of every month
    fun enqueueMonthlyCardStatement() {
        val yearMonth = YearMonth.now().toString()
        val dedupId = "$TASK_TYPE-$yearMonth"
        runCatching {
            taskQueue.enqueue(
                taskType = TASK_TYPE,
                payload = objectMapper.writeValueAsString(mapOf("yearMonth" to yearMonth)),
                groupId = TASK_TYPE,
                deduplicationId = dedupId,
            )
        }.onFailure {
            logger
                .atError()
                .addKeyValue("year_month", yearMonth)
                .setCause(it)
                .log("Failed to enqueue the monthly card statement Task")
        }
    }

    companion object {
        const val TASK_TYPE = "card.send-statement"
    }
}
