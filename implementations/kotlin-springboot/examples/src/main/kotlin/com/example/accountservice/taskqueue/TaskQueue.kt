package com.example.accountservice.taskqueue

/**
 * Task를 적재하기 위한 포트(docs/architecture/scheduling.md의 `TaskQueue` 예시와 동일한 이름·역할).
 * Scheduler(Infrastructure 레이어, `@Scheduled` 메서드)가 이 인터페이스만 의존한다 — 실제로는
 * SQS에 바로 보내지 않고 [TaskOutboxWriter]가 `task_outbox` 테이블에 한 행을 적재할 뿐이다("적재는
 * Outbox 경유" 원칙, dual-write 방지).
 *
 * Domain Event([com.example.accountservice.outbox.OutboxWriter]가 받는 `List<Any>`)와 달리 Task는
 * "명령: X를 수행하라"이므로 이벤트 객체가 아니라 taskType(문자열 식별자) + payload(JSON 문자열)로
 * 표현한다 — root scheduling.md/domain-events.md가 규정하는 Task Queue vs Domain Event 구분이
 * 타입 시그니처에도 그대로 드러난다.
 */
interface TaskQueue {
    /**
     * @param taskType 예: `"account.pay-interest"`, `"card.send-statement"` — [TaskHandlerRegistry]가
     *   이 값으로 핸들러를 찾는다.
     * @param payload 이미 직렬화된 JSON 문자열 — 대용량 데이터는 담지 않는다(SQS 메시지 256KB 제한,
     *   scheduling.md "payload 크기 제한").
     * @param groupId FIFO MessageGroupId — 이 저장소는 taskType당 하나의 배치 Task만 다루므로
     *   taskType 자체를 groupId로 쓴다(scheduling.md "Cron 전역 배치" 행).
     * @param deduplicationId FIFO MessageDeduplicationId이자 `task_outbox.deduplication_id`
     *   UNIQUE 제약의 값 — `<taskType>-<date>`/`<taskType>-<yearMonth>` 형태로 호출자가 만든다.
     */
    fun enqueue(
        taskType: String,
        payload: String,
        groupId: String,
        deduplicationId: String,
    )
}
