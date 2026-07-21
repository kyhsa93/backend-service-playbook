package com.example.accountservice.taskqueue

import com.example.accountservice.account.interfaces.task.PayInterestTaskController
import com.example.accountservice.card.interfaces.task.SendCardStatementTaskController
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * taskType(`task_outbox.task_type` = SQS `MessageAttributes.taskType`) → 핸들러 함수 매핑.
 * [TaskQueueConsumer]가 SQS에서 메시지를 수신했을 때 이 레지스트리로 라우팅한다 —
 * [com.example.accountservice.outbox.EventHandlerRegistry]와 동일한 모양(생성자 주입 시점에
 * `Map<taskType, handler>`를 구성)이지만, Domain Event(1:N, 여러 핸들러가 구독)와 달리 Task는
 * taskType당 정확히 하나의 핸들러만 존재한다(root domain-events.md "Task Queue vs Domain
 * Event" — 핸들러 수 1:1).
 *
 * payload(JSON)를 여기서 파싱해 Task Controller에 타입이 있는 인자로 넘긴다 — EventHandlerRegistry가
 * payload를 Domain Event 타입으로 역직렬화해 핸들러에 넘기는 것과 같은 책임 분담이다.
 */
@Component
class TaskHandlerRegistry(
    private val objectMapper: ObjectMapper,
    private val payInterestTaskController: PayInterestTaskController,
    private val sendCardStatementTaskController: SendCardStatementTaskController,
) {
    private val logger = LoggerFactory.getLogger(TaskHandlerRegistry::class.java)

    private val handlers: Map<String, (payload: String) -> Unit> =
        mapOf(
            "account.pay-interest" to { payload ->
                val date = LocalDate.parse(objectMapper.readTree(payload).get("date").asText())
                payInterestTaskController.payInterest(date)
            },
            "card.send-statement" to { payload ->
                val yearMonth = objectMapper.readTree(payload).get("yearMonth").asText()
                sendCardStatementTaskController.sendStatements(yearMonth)
            },
        )

    /** 등록된 taskType 집합 — 진단/테스트 용도. */
    fun registeredTaskTypes(): Set<String> = handlers.keys

    /**
     * [TaskQueueConsumer]가 SQS 메시지 하나를 수신할 때마다 호출한다. 등록된 핸들러가 없으면 경고만
     * 남기고 조용히 반환한다(알 수 없는 taskType을 무한 재시도할 이유가 없다). 핸들러가 예외를
     * 던지면 그대로 전파해 [TaskQueueConsumer]가 메시지를 삭제하지 않고 SQS 재전달(at-least-once)에
     * 맡기게 한다.
     */
    fun dispatch(
        taskType: String,
        payload: String,
    ) {
        val handler = handlers[taskType]
        if (handler == null) {
            logger.warn("알 수 없는 taskType: {}", taskType)
            return
        }
        handler(payload)
    }
}
