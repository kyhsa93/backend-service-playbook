package com.example.accountservice.card.application.command

import com.example.accountservice.card.application.adapter.AccountAdapter
import com.example.accountservice.card.application.adapter.PaymentAdapter
import com.example.accountservice.card.domain.CardFindQuery
import com.example.accountservice.card.domain.CardRepository
import com.example.accountservice.card.domain.CardStatus
import com.example.accountservice.notification.application.service.NotificationService
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * 매월 카드 사용내역 명세서 발송 유스케이스 —
 * [com.example.accountservice.card.interfaces.task.SendCardStatementTaskController](Task Queue의
 * `card.send-statement` 핸들러)가 호출한다. PayInterestService와 마찬가지로 시스템(Scheduler)이
 * 발생시키는 배치 작업이다.
 *
 * "지난 한 달"은 엄밀한 캘린더 월 경계가 아니라 **실행 시점 기준 최근 [STATEMENT_WINDOW_DAYS]일**로
 * 근사한다(요구사항 문구가 "roughly the past month"임을 그대로 반영한 단순화 — 정확한 청구
 * 마감일이 필요한 회계/정산 기능이었다면 payload에 확정된 기간을 담아야 하지만, 이 명세서는 참고용
 * 요약이므로 재시도 시점에 따라 창이 몇 시간 밀려도 무해하다). 반면 중복 발송 방지 키([yearMonth])는
 * Scheduler가 enqueue 시점에 고정해 payload로 넘긴 값을 그대로 쓴다 — "이번 tick이 몇 월 몫인가"는
 * 재시도 여부와 무관하게 고정돼야 하기 때문이다.
 *
 * Repository 조회 단계에서 `status = ACTIVE`이면서 `excludeStatementMonth = yearMonth`(이번 달에
 * 이미 명세서를 보낸 카드 제외)로 걸러낸다.
 *
 * 순서가 중요하다 — [NotificationService.sendEmail]을 먼저 호출하고, 성공한 뒤에야
 * [com.example.accountservice.card.domain.Card.markStatementSent]로 카드 상태를 갱신한다. 이렇게
 * 하면 두 가지 실패 모드 모두 안전하다: (1) 이메일 발송이 실패하면 카드가 "발송됨"으로 잘못
 * 기록되지 않으므로 다음 tick에서 정상적으로 재시도된다. (2) 이메일은 성공했지만 그 직후
 * `saveCard`가 실패해도, `NotificationService`가 `sourceEventId` 기준 Level 2(Ledger) 중복 발송
 * 방지를 이미 갖고 있으므로(domain-events.md) 다음 tick의 재발송 시도는 실제로는 스킵된다 — Card의
 * `lastStatementSentMonth`(Level 1)와 NotificationService의 Ledger(Level 2)가 이중으로 멱등성을
 * 보장한다.
 */
@Service
class SendMonthlyCardStatementsService(
    private val cardRepository: CardRepository,
    private val paymentAdapter: PaymentAdapter,
    private val accountAdapter: AccountAdapter,
    private val notificationService: NotificationService,
) {
    fun sendStatements(yearMonth: String) {
        val (cards, _) =
            cardRepository.findCards(
                CardFindQuery(
                    page = 0,
                    take = MAX_CARDS_PER_RUN,
                    status = listOf(CardStatus.ACTIVE),
                    excludeStatementMonth = yearMonth,
                ),
            )
        val windowEnd = LocalDateTime.now()
        val windowStart = windowEnd.minusDays(STATEMENT_WINDOW_DAYS)

        for (card in cards) {
            val account = accountAdapter.findAccount(card.accountId, card.ownerId) ?: continue
            val summary = paymentAdapter.summarizePayments(card.cardId, windowStart, windowEnd)

            notificationService.sendEmail(
                accountId = card.accountId,
                eventType = "CardStatement",
                sourceEventId = "card.statement-${card.cardId}-$yearMonth",
                recipient = account.email,
                subject = "[Card] $yearMonth 카드 사용내역 안내",
                body =
                    "카드(${card.cardId}) 최근 ${STATEMENT_WINDOW_DAYS}일간 결제 ${summary.count}건, " +
                        "총 ${summary.totalAmount}원이 청구되었습니다.",
            )

            card.markStatementSent(yearMonth)
            cardRepository.saveCard(card)
        }
    }

    companion object {
        // CancelCardsByAccountService(take = 1000)와 동일한 취지 — REST 페이지네이션 대상이 아닌
        // 내부 배치 루프이므로 충분히 크게 준다. PayInterestService.MAX_ACCOUNTS_PER_RUN과 동일한
        // "알려진 한계"(대규모 실사용 시 커서 기반 재설계 필요)가 적용된다.
        private const val MAX_CARDS_PER_RUN = 10_000
        private const val STATEMENT_WINDOW_DAYS = 30L
    }
}
