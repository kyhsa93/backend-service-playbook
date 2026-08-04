package com.example.accountservice.account.infrastructure.scheduling

import com.example.accountservice.taskqueue.TaskQueue
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The recurring monthly spending-forecast Scheduler — placed in the Infrastructure layer
 * (scheduling.md, "A Scheduler is placed in the Infrastructure layer"), the same role/structure as
 * [SpendingAnalysisScheduler]. It only enqueues a Task onto the [TaskQueue]; the actual
 * training/prediction is handled by
 * [com.example.accountservice.account.interfaces.task.ForecastSpendingTaskController] →
 * `ForecastSpendingService`, which receives the `account.forecast-spending` Task.
 *
 * Runs at 06:00 UTC on the 1st of every month — an hour after this account service's own
 * spending-analysis job ([SpendingAnalysisScheduler], 05:00 UTC), so this month's history (last
 * month's freshly-written analysis row) is guaranteed to exist before training reads it. The
 * period is computed against the UTC calendar for the same reason SpendingAnalysisScheduler does.
 */
@Component
class SpendingForecastScheduler(
    private val taskQueue: TaskQueue,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(SpendingForecastScheduler::class.java)

    @Scheduled(cron = "0 0 6 1 * *", zone = "UTC")
    fun enqueueMonthlySpendingForecast() {
        val forecastMonth = computeSpendingForecastMonth(LocalDateTime.now(ZoneOffset.UTC))
        val dedupId = "$TASK_TYPE-$forecastMonth"

        // Exceptions from the Cron handler are caught explicitly and logged so the scheduling
        // framework does not silently swallow them (scheduling.md, "Cron exceptions are logged
        // explicitly").
        runCatching {
            taskQueue.enqueue(
                taskType = TASK_TYPE,
                payload = objectMapper.writeValueAsString(mapOf("forecastMonth" to forecastMonth)),
                groupId = GROUP_ID,
                deduplicationId = dedupId,
            )
        }.onFailure {
            logger
                .atError()
                .addKeyValue("forecast_month", forecastMonth)
                .setCause(it)
                .log("Failed to enqueue the monthly spending-forecast Task")
        }
    }

    companion object {
        const val TASK_TYPE = "account.forecast-spending"
        private const val GROUP_ID = "account.spending-forecast"
    }
}

/**
 * A pure function computing account.forecast-spending's target period — "the current month" (the
 * one that just started, when this runs on the 1st at 06:00 UTC, an hour after the
 * spending-analysis job at 05:00 has finished writing last month's row). The Scheduler carries
 * this function's result through the Task payload as-is — the same reason as
 * [computePreviousSpendingAnalysisPeriod]: recomputing "which month" from the clock at processing
 * time (rather than at enqueue time) could target the wrong month if processing is delayed by a
 * queue backlog.
 */
fun computeSpendingForecastMonth(now: LocalDateTime): String = "%04d-%02d".format(now.year, now.monthValue)
