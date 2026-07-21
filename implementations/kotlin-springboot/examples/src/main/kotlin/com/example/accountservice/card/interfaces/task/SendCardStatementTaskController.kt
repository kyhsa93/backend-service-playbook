package com.example.accountservice.card.interfaces.task

import com.example.accountservice.card.application.command.SendMonthlyCardStatementsService
import org.springframework.stereotype.Component

/**
 * Task Queue(`card.send-statement`)의 Interface 입력 어댑터 —
 * [com.example.accountservice.account.interfaces.task.PayInterestTaskController]와 동일한 역할·구조다
 * (scheduling.md "Task Controller — Interface 레이어" 참고). 로직 없이 Command 위임만 하고, 예외는
 * 그대로 던져 [com.example.accountservice.taskqueue.TaskQueueConsumer]가 재시도/DLQ를 판단하게 한다.
 */
@Component
class SendCardStatementTaskController(
    private val sendMonthlyCardStatementsService: SendMonthlyCardStatementsService,
) {
    fun sendStatements(yearMonth: String) {
        sendMonthlyCardStatementsService.sendStatements(yearMonth)
    }
}
