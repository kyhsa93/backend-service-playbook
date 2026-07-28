package com.example.accountservice.account.application.event;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.account.application.service.NotificationService;
import com.example.accountservice.account.domain.AnomalyDetectionService;
import com.example.accountservice.account.domain.MoneyWithdrawnEvent;
import com.example.accountservice.account.domain.TransactionRepository;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reacts to {@link MoneyWithdrawnEvent} (registered alongside {@code MoneyWithdrawnEventHandler}
 * and {@code CategorizeTransactionEventHandler} — this is now the 3rd subscriber; {@code
 * OutboxEventDispatcher} supports multiple subscribers per event type) to flag a withdrawal that's
 * a statistical outlier against the account's own history.
 *
 * <p>Deliberately only ever sends a Notification — it never blocks, reverses, or judges the
 * withdrawal itself (the withdrawal already completed before this even runs). This is the design
 * constraint that keeps it out of the domain-purity trap the earlier RefundFraudRiskScorer/
 * RefundReasonClassifier fell into (both removed — see root docs/architecture/domain-service.md's
 * Domain Service section): a signal that only ever informs a human, never one a Domain Service
 * treats as a judgment input.
 */
@Component
@RequiredArgsConstructor
public class DetectWithdrawalAnomalyEventHandler implements OutboxEventHandler {

    private static final Logger log =
            LoggerFactory.getLogger(DetectWithdrawalAnomalyEventHandler.class);

    // How many of the account's own most recent (excluding this one) withdrawals
    // AnomalyDetectionService trains its mean/standard deviation against.
    private static final int HISTORY_WINDOW = 30;

    private final TransactionRepository transactionRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    // A pure Domain Service with no framework annotations — instantiated directly with new, the
    // same pattern as TransferEligibilityService/RefundEligibilityService.
    private final AnomalyDetectionService anomalyDetectionService = new AnomalyDetectionService();

    @Override
    public String eventType() {
        return MoneyWithdrawnEvent.class.getSimpleName();
    }

    @Override
    public void handle(String payload) throws Exception {
        MoneyWithdrawnEvent event = objectMapper.readValue(payload, MoneyWithdrawnEvent.class);

        List<Long> history =
                transactionRepository.findRecentWithdrawalAmounts(
                        event.accountId(), event.transactionId(), HISTORY_WINDOW);
        boolean isAnomalous = anomalyDetectionService.isAnomalous(history, event.amount().amount());
        if (!isAnomalous) {
            return;
        }

        notificationService.sendEmail(
                event.accountId(),
                "WithdrawalAnomalyDetected",
                event.email(),
                "[Account] Unusual withdrawal detected",
                "A withdrawal of "
                        + event.amount().amount()
                        + " "
                        + event.amount().currency()
                        + " is unusually large compared to your recent activity. If this wasn't"
                        + " you, please contact support immediately.");
        log.info(
                "Anomalous withdrawal detected, alert sent",
                kv("account_id", event.accountId()),
                kv("transaction_id", event.transactionId()),
                kv("amount", event.amount().amount()));
    }
}
