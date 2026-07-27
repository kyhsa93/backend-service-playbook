package com.example.accountservice.account.infrastructure.scheduling

import com.example.accountservice.taskqueue.TaskQueue
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The recurring monthly spending-analysis Scheduler — placed in the Infrastructure layer
 * (scheduling.md, "A Scheduler is placed in the Infrastructure layer"), the same
 * role/structure as [com.example.accountservice.account.infrastructure.scheduling.InterestPaymentScheduler]/
 * [com.example.accountservice.card.infrastructure.scheduling.CardStatementScheduler]. It only enqueues
 * a Task onto the [TaskQueue]; the actual aggregation is handled by
 * [com.example.accountservice.account.interfaces.task.AnalyzeMonthlySpendingTaskController] →
 * `AnalyzeMonthlySpendingService`, which receives the `account.analyze-monthly-spending` Task.
 *
 * Runs at 05:00 UTC on the 1st of every month — an hour after the monthly card-statement job (04:00),
 * to avoid both batch jobs contending for the database at the same moment. The period is computed
 * explicitly against the UTC calendar ([java.time.ZoneOffset.UTC], not the server's local zone) so
 * "which month is being analyzed" doesn't depend on where the JVM happens to be deployed.
 */
@Component
class SpendingAnalysisScheduler(
    private val taskQueue: TaskQueue,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(SpendingAnalysisScheduler::class.java)

    @Scheduled(cron = "0 0 5 1 * *", zone = "UTC")
    fun enqueueMonthlySpendingAnalysis() {
        val period = computePreviousSpendingAnalysisPeriod(LocalDateTime.now(ZoneOffset.UTC))
        val dedupId = "$TASK_TYPE-${period.analysisMonth}"

        // Exceptions from the Cron handler are caught explicitly and logged so the scheduling
        // framework does not silently swallow them (scheduling.md, "Cron exceptions are logged
        // explicitly").
        runCatching {
            taskQueue.enqueue(
                taskType = TASK_TYPE,
                payload =
                    objectMapper.writeValueAsString(
                        mapOf(
                            "analysisMonth" to period.analysisMonth,
                            "monthStart" to period.monthStart.toString(),
                            "monthEnd" to period.monthEnd.toString(),
                            "previousMonthStart" to period.previousMonthStart.toString(),
                            "previousMonthEnd" to period.previousMonthEnd.toString(),
                        ),
                    ),
                groupId = TASK_TYPE,
                deduplicationId = dedupId,
            )
        }.onFailure {
            logger
                .atError()
                .addKeyValue("analysis_month", period.analysisMonth)
                .setCause(it)
                .log("Failed to enqueue the monthly spending-analysis Task")
        }
    }

    companion object {
        const val TASK_TYPE = "account.analyze-monthly-spending"
    }
}
