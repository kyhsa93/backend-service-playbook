package com.example.accountservice.card.interfaces.task

import com.example.accountservice.card.application.command.SendMonthlyCardStatementsService
import org.springframework.stereotype.Component

/**
 * Interface input adapter for the Task Queue (`card.send-statement`) — the same role/structure as
 * [com.example.accountservice.account.interfaces.task.PayInterestTaskController] (see scheduling.md's
 * "Task Controller — Interface layer" section). Contains no logic, only delegates to the Command; any
 * exception is left to propagate so [com.example.accountservice.taskqueue.TaskQueueConsumer] can decide
 * whether to retry or send to the DLQ.
 */
@Component
class SendCardStatementTaskController(
    private val sendMonthlyCardStatementsService: SendMonthlyCardStatementsService,
) {
    fun sendStatements(yearMonth: String) {
        sendMonthlyCardStatementsService.sendStatements(yearMonth)
    }
}
