package com.example.accountservice.card.interfaces.integrationevent

import com.example.accountservice.card.application.command.CancelCardsByAccountService
import com.example.accountservice.card.application.command.SuspendCardsByAccountService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 외부 BC(Account)가 발행한 Integration Event를 수신하는 Interface 입력 어댑터.
 * HTTP Controller와 동일한 위치(interfaces/)의 입력 경계다 — 자기 도메인의 유스케이스(Command Service)만
 * 호출하고, 예외는 그대로 던져 [com.example.accountservice.outbox.OutboxConsumer]가 재시도(메시지를
 * 삭제하지 않아 SQS visibility timeout 이후 재전달)를 담당하게 한다.
 *
 * 이 컴포넌트는 [com.example.accountservice.outbox.EventHandlerRegistry] 생성자에 주입되어
 * `account.suspended.v1`/`account.closed.v1` 라우팅 항목에서 호출된다. Account 공개 계약(payload)에서
 * accountId만 추출해 넘겨받으므로 Card는 Account의 Integration Event 클래스에 의존하지 않는다.
 */
@Component
class CardIntegrationEventController(
    private val suspendCardsByAccountService: SuspendCardsByAccountService,
    private val cancelCardsByAccountService: CancelCardsByAccountService,
) {
    private val logger = LoggerFactory.getLogger(CardIntegrationEventController::class.java)

    fun onAccountSuspended(accountId: String) {
        logger.atInfo().addKeyValue("account_id", accountId).log("account.suspended.v1 수신")
        suspendCardsByAccountService.suspend(accountId)
    }

    fun onAccountClosed(accountId: String) {
        logger.atInfo().addKeyValue("account_id", accountId).log("account.closed.v1 수신")
        cancelCardsByAccountService.cancel(accountId)
    }
}
