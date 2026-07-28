package com.example.accountservice.account.interfaces.task

import com.example.accountservice.account.application.command.ForecastSpendingService
import org.springframework.stereotype.Component

/**
 * The Interface input adapter for the Task Queue (`account.forecast-spending`) — the same
 * role/structure as
 * [com.example.accountservice.account.interfaces.task.AnalyzeMonthlySpendingTaskController]. It
 * only delegates to the Command Service, with no logic of its own; exceptions are thrown as-is so
 * [com.example.accountservice.taskqueue.TaskQueueConsumer] leaves the message to SQS redelivery
 * instead of deleting it.
 */
@Component
class ForecastSpendingTaskController(
    private val forecastSpendingService: ForecastSpendingService,
) {
    fun forecastSpending(forecastMonth: String) {
        forecastSpendingService.forecastSpending(forecastMonth)
    }
}
