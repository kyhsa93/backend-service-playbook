package com.example.accountservice.account.interfaces.task

import com.example.accountservice.account.application.command.PayInterestService
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * Task Queue(`account.pay-interest`)의 Interface 입력 어댑터(scheduling.md "Task Controller —
 * Interface 레이어"). HTTP Controller가 HTTP 요청을 받아 Application Service에 위임하듯, 이
 * 컴포넌트는 [com.example.accountservice.taskqueue.TaskHandlerRegistry]가 SQS 메시지를 역직렬화한
 * 뒤 호출하는 진입점이다.
 *
 * 로직 없이 Command 위임만 한다 — 조건 분기·비즈니스 규칙을 넣지 않는다. 예외는 그대로 던진다.
 * [com.example.accountservice.taskqueue.TaskQueueConsumer]가 이를 잡아 메시지를 삭제하지 않고
 * SQS 재전달(at-least-once)에 맡긴다 — HTTP Controller의 `@ExceptionHandler` 변환 패턴과 다르다.
 */
@Component
class PayInterestTaskController(
    private val payInterestService: PayInterestService,
) {
    fun payInterest(payDate: LocalDate) {
        payInterestService.payInterest(payDate)
    }
}
