package com.example.accountservice.payment.application.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accountservice.payment.application.service.RefundReasonClassifier;
import com.example.accountservice.payment.domain.Refund;
import com.example.accountservice.payment.domain.RefundReasonCategory;
import com.example.accountservice.payment.domain.RefundRepository;
import com.example.accountservice.payment.domain.RefundStatus;
import com.example.accountservice.payment.domain.RefundsWithCount;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClassifyRefundReasonEventHandlerTest {

    @Mock private RefundReasonClassifier refundReasonClassifier;
    @Mock private RefundRepository refundRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private ClassifyRefundReasonEventHandler handler;

    private final Refund refund =
            Refund.reconstitute(
                    "refund-1",
                    "payment-1",
                    5000,
                    "The item arrived broken",
                    RefundStatus.APPROVED,
                    null,
                    null,
                    LocalDateTime.now());

    @BeforeEach
    void setUp() {
        handler =
                new ClassifyRefundReasonEventHandler(
                        refundReasonClassifier, refundRepository, objectMapper);
    }

    private String eventPayload(String refundId, String reason) throws Exception {
        Map<String, Object> event = new HashMap<>();
        event.put("refundId", refundId);
        event.put("paymentId", "payment-1");
        event.put("reason", reason);
        event.put("createdAt", LocalDateTime.now().toString());
        return objectMapper.writeValueAsString(event);
    }

    @Test
    void handle_when_the_refund_still_exists_then_classifies_and_saves_the_category()
            throws Exception {
        when(refundRepository.findRefunds(any()))
                .thenReturn(new RefundsWithCount(List.of(refund), 1));
        when(refundReasonClassifier.classify("The item arrived broken"))
                .thenReturn(RefundReasonCategory.DEFECTIVE_PRODUCT);

        handler.handle(eventPayload("refund-1", "The item arrived broken"));

        verify(refundReasonClassifier).classify("The item arrived broken");
        verify(refundRepository)
                .saveRefund(
                        argThat(
                                r ->
                                        r.getRefundId().equals("refund-1")
                                                && r.getReasonCategory()
                                                        == RefundReasonCategory.DEFECTIVE_PRODUCT));
    }

    @Test
    void handle_when_the_refund_no_longer_exists_then_skips_classification_without_throwing()
            throws Exception {
        when(refundRepository.findRefunds(any())).thenReturn(new RefundsWithCount(List.of(), 0));

        handler.handle(eventPayload("refund-1", "The item arrived broken"));

        verify(refundReasonClassifier, never()).classify(any());
        verify(refundRepository, never()).saveRefund(any());
    }
}
