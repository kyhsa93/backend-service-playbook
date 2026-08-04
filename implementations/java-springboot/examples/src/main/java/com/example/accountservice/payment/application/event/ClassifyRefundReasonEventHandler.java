package com.example.accountservice.payment.application.event;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.outbox.OutboxEventHandler;
import com.example.accountservice.payment.application.service.RefundReasonClassifier;
import com.example.accountservice.payment.domain.Refund;
import com.example.accountservice.payment.domain.RefundFindQuery;
import com.example.accountservice.payment.domain.RefundReasonCategory;
import com.example.accountservice.payment.domain.RefundRepository;
import com.example.accountservice.payment.domain.RefundRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reacts to {@link RefundRequestedEvent} (published unconditionally by {@link Refund#create},
 * before {@code RefundEligibilityService}'s approve/reject judgment even runs) to classify the
 * refund's free-text reason for ops-analytics reporting only — see {@code
 * RefundReasonInsightsQuery}. Runs off the request hot path ({@code RequestRefundService} never
 * calls an LLM directly), and its result is never read back into any eligibility/approval decision.
 * Inherently idempotent: a retried delivery just re-runs the same find→categorize→save cycle.
 */
@Component
@RequiredArgsConstructor
public class ClassifyRefundReasonEventHandler implements OutboxEventHandler {

    private static final Logger log =
            LoggerFactory.getLogger(ClassifyRefundReasonEventHandler.class);

    private final RefundReasonClassifier refundReasonClassifier;
    private final RefundRepository refundRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return RefundRequestedEvent.class.getSimpleName();
    }

    @Override
    public void handle(String payload) throws Exception {
        RefundRequestedEvent event = objectMapper.readValue(payload, RefundRequestedEvent.class);

        Refund refund =
                refundRepository
                        .findRefunds(new RefundFindQuery(0, 1, event.refundId(), null))
                        .refunds()
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (refund == null) {
            return;
        }

        RefundReasonCategory category = refundReasonClassifier.classify(event.reason());
        refund.categorizeReason(category);
        refundRepository.saveRefund(refund);

        log.info(
                "Refund reason classified",
                kv("refund_id", event.refundId()),
                kv("category", category));
    }
}
