package com.example.accountservice.card.infrastructure.scheduling

import com.example.accountservice.taskqueue.TaskQueue
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.YearMonth

/**
 * 매월 카드 사용내역 명세서 발송 Scheduler — Infrastructure 레이어
 * ([com.example.accountservice.account.infrastructure.scheduling.InterestPaymentScheduler]와
 * 동일한 역할·구조). Task를 [TaskQueue]에 적재만 하고, 실제 집계/발송은 `card.send-statement`
 * Task를 수신하는 [com.example.accountservice.card.interfaces.task.SendCardStatementTaskController] →
 * `SendMonthlyCardStatementsService`가 담당한다.
 *
 * [yearMonth](enqueue 시점의 현재 월)는 중복 발송 방지 키로만 쓰인다 — 실제 집계 기간(최근 30일)은
 * `SendMonthlyCardStatementsService`가 Task 처리 시점 기준으로 별도 계산한다(그 클래스 KDoc 참고).
 */
@Component
class CardStatementScheduler(
    private val taskQueue: TaskQueue,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(CardStatementScheduler::class.java)

    @Scheduled(cron = "0 0 4 1 * *") // 매월 1일 04:00
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
                .log("월간 카드 명세서 Task 적재 실패")
        }
    }

    companion object {
        const val TASK_TYPE = "card.send-statement"
    }
}
