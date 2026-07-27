package com.example.accountservice.account.interfaces.task

import com.example.accountservice.account.application.command.AnalyzeMonthlySpendingService
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * The Interface input adapter for the Task Queue (`account.analyze-monthly-spending`) — the same
 * role/structure as [com.example.accountservice.account.interfaces.task.PayInterestTaskController]. It
 * only delegates to the Command Service, with no logic of its own; exceptions are thrown as-is so
 * [com.example.accountservice.taskqueue.TaskQueueConsumer] leaves the message to SQS redelivery
 * instead of deleting it.
 */
@Component
class AnalyzeMonthlySpendingTaskController(
    private val analyzeMonthlySpendingService: AnalyzeMonthlySpendingService,
) {
    fun analyzeMonthlySpending(
        analysisMonth: String,
        monthStart: LocalDateTime,
        monthEnd: LocalDateTime,
        previousMonthStart: LocalDateTime,
        previousMonthEnd: LocalDateTime,
    ) {
        analyzeMonthlySpendingService.analyzeMonthlySpending(analysisMonth, monthStart, monthEnd, previousMonthStart, previousMonthEnd)
    }
}
