package com.example.accountservice.account.application.event

import com.example.accountservice.account.domain.AnomalyDetectionService
import com.example.accountservice.account.domain.MoneyWithdrawnEvent
import com.example.accountservice.account.domain.TransactionRepository
import com.example.accountservice.notification.application.service.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// How many of the account's own most recent (excluding this one) withdrawals
// AnomalyDetectionService trains its mean/standard deviation against.
private const val HISTORY_WINDOW = 30

/**
 * Reacts to [MoneyWithdrawnEvent] (registered in [com.example.accountservice.outbox.EventHandlerRegistry]
 * alongside [MoneyWithdrawnEventHandler] and [CategorizeTransactionEventHandler] — this is now the
 * 3rd subscriber; the registry supports multiple subscribers per event type) to flag a withdrawal
 * that's a statistical outlier against the account's own history.
 *
 * Deliberately only ever sends a Notification — it never blocks, reverses, or judges the
 * withdrawal itself (the withdrawal already completed before this even runs). This is the design
 * constraint that keeps it out of the domain-purity trap the earlier RefundFraudRiskScorer/
 * RefundReasonClassifier fell into (both removed — see root docs/architecture/domain-service.md's
 * Domain Service section): a signal that only ever informs a human, never one a Domain Service
 * treats as a judgment input.
 *
 * Exceptions are not caught and are thrown as-is — [com.example.accountservice.outbox.OutboxConsumer]
 * catches them, logs, and leaves the Outbox row as processed=false so the next call retries it
 * (at-least-once delivery).
 */
@Component
class DetectWithdrawalAnomalyEventHandler(
    private val transactionRepository: TransactionRepository,
    private val notificationService: NotificationService,
) {
    private val logger = LoggerFactory.getLogger(DetectWithdrawalAnomalyEventHandler::class.java)

    // A pure Domain Service with no framework annotations — instantiated directly with `new`, the
    // same pattern as TransferEligibilityService/RefundEligibilityService.
    private val anomalyDetectionService = AnomalyDetectionService()

    fun handle(
        event: MoneyWithdrawnEvent,
        eventId: String,
    ) {
        val history =
            transactionRepository.findRecentWithdrawalAmounts(event.accountId, event.transactionId, HISTORY_WINDOW)
        val isAnomalous = anomalyDetectionService.isAnomalous(history, event.amount.amount)
        if (!isAnomalous) return

        notificationService.sendEmail(
            accountId = event.accountId,
            eventType = "WithdrawalAnomalyDetected",
            sourceEventId = eventId,
            recipient = event.email,
            subject = "[Account] Unusual withdrawal detected",
            body =
                "A withdrawal of ${event.amount.amount} ${event.amount.currency} is unusually large compared to your " +
                    "recent activity. If this wasn't you, please contact support immediately.",
        )
        logger.info(
            "Anomalous withdrawal detected, alert sent transaction_id={} amount={}",
            event.transactionId,
            event.amount.amount,
        )
    }
}
