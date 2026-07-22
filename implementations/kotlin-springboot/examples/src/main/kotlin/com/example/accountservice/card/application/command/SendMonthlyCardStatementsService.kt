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
 * The monthly card-usage-statement delivery use case — called by
 * [com.example.accountservice.card.interfaces.task.SendCardStatementTaskController] (the Task Queue's
 * `card.send-statement` handler). Like PayInterestService, this is a batch job triggered by the
 * system (Scheduler).
 *
 * "The past month" is not a strict calendar-month boundary but is approximated as **the most recent
 * [STATEMENT_WINDOW_DAYS] days from the execution time** (a simplification that directly reflects the
 * requirement wording "roughly the past month" — if this were an accounting/settlement feature that
 * needed an exact billing cutoff, the payload would need to carry a fixed period, but since this
 * statement is a reference-only summary, it's harmless for the window to drift by a few hours
 * depending on retry timing). The duplicate-send-prevention key ([yearMonth]), on the other hand, uses
 * the value the Scheduler fixed at enqueue time and passed through the payload as-is — "which month
 * this tick belongs to" must stay fixed regardless of retries.
 *
 * At the Repository query step, cards are filtered to `status = ACTIVE` and
 * `excludeStatementMonth = yearMonth` (excluding cards that have already been sent this month's
 * statement).
 *
 * Order matters — [NotificationService.sendEmail] is called first, and only after it succeeds is the
 * card's state updated via [com.example.accountservice.card.domain.Card.markStatementSent]. This makes
 * both failure modes safe: (1) if the email send fails, the card is not incorrectly recorded as
 * "sent", so it is retried normally on the next tick. (2) if the email succeeds but `saveCard` fails
 * immediately afterward, the next tick's resend attempt is effectively skipped anyway, because
 * `NotificationService` already has Level 2 (Ledger) duplicate-send prevention keyed on
 * `sourceEventId` (domain-events.md) — Card's `lastStatementSentMonth` (Level 1) and
 * NotificationService's Ledger (Level 2) together guarantee idempotency in two layers.
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
                subject = "[Card] $yearMonth Card Usage Statement",
                body =
                    "Card (${card.cardId}) had ${summary.count} payment(s) over the last ${STATEMENT_WINDOW_DAYS} days, " +
                        "totaling ${summary.totalAmount} KRW.",
            )

            card.markStatementSent(yearMonth)
            cardRepository.saveCard(card)
        }
    }

    companion object {
        // Same intent as CancelCardsByAccountService(take = 1000) — this is an internal batch loop,
        // not subject to REST pagination, so it is given a sufficiently large value. The same "known
        // limitation" as PayInterestService.MAX_ACCOUNTS_PER_RUN applies (a cursor-based redesign is
        // needed at large real-world scale).
        private const val MAX_CARDS_PER_RUN = 10_000
        private const val STATEMENT_WINDOW_DAYS = 30L
    }
}
