package com.example.accountservice.account.infrastructure.scheduling

import com.example.accountservice.taskqueue.TaskQueue
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 정기 이자 지급 Scheduler — Infrastructure 레이어(scheduling.md "Scheduler는 Infrastructure
 * 레이어에 배치"). 비즈니스 로직을 직접 실행하지 않고 Task를 [TaskQueue]에 적재(enqueue)만 한다 —
 * 실제 이자 계산/적립은 `account.pay-interest` Task를 수신하는
 * [com.example.accountservice.account.interfaces.task.PayInterestTaskController] →
 * `PayInterestService`가 담당한다.
 *
 * [payDate]를 enqueue 시점에 확정해 payload로 넘긴다 — Task 처리(Consumer)가 지연되더라도(예: 자정
 * 근처) "몇 월 며칠자 이자인가"가 재계산되지 않고 enqueue 시점 그대로 유지된다. 이 값이 그대로
 * `Account.payInterest(payDate)`의 멱등성 키([com.example.accountservice.account.domain.Account.lastInterestPaidAt]
 * 비교 대상)로 쓰인다.
 */
@Component
class InterestPaymentScheduler(
    private val taskQueue: TaskQueue,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(InterestPaymentScheduler::class.java)

    @Scheduled(cron = "0 0 3 * * *") // 매일 03:00
    fun enqueueDailyInterestPayment() {
        val payDate = LocalDate.now()
        val dedupId = "$TASK_TYPE-$payDate"
        // Cron 핸들러에서 발생한 예외를 스케줄링 프레임워크가 조용히 삼키지 않도록 명시적으로 잡아
        // 로깅한다(scheduling.md "Cron 예외는 명시적으로 로깅한다"). enqueue 자체가 실패하면(DB
        // 장애 등) 오늘자 Task는 유실된다 — 다음 tick은 내일 날짜로 enqueue하므로 오늘을 자동으로
        // 재시도하지 않는다. 이 저장소 범위에서는 이 로그(ERROR 레벨)를 알람으로 감시해 필요 시
        // 수동으로 재실행하는 것으로 충분하다고 본다.
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
                .log("일일 이자 지급 Task 적재 실패")
        }
    }

    companion object {
        const val TASK_TYPE = "account.pay-interest"
    }
}
