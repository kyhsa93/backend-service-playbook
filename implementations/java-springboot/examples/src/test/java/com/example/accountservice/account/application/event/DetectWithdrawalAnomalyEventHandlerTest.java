package com.example.accountservice.account.application.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accountservice.account.application.service.NotificationService;
import com.example.accountservice.account.domain.TransactionRepository;
import com.example.accountservice.common.UtcClock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class DetectWithdrawalAnomalyEventHandlerTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private NotificationService notificationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DetectWithdrawalAnomalyEventHandler handler;

    @BeforeEach
    void setUp() {
        handler =
                new DetectWithdrawalAnomalyEventHandler(
                        transactionRepository, notificationService, objectMapper);
    }

    private String eventPayload(long amount) throws Exception {
        Map<String, Object> event = new HashMap<>();
        event.put("accountId", "account-1");
        event.put("email", "owner-1@example.com");
        event.put("transactionId", "transaction-1");
        event.put("amount", Map.of("amount", amount, "currency", "KRW"));
        event.put("balanceAfter", Map.of("amount", 4500, "currency", "KRW"));
        event.put("merchantName", null);
        event.put("createdAt", UtcClock.now().toString());
        return objectMapper.writeValueAsString(event);
    }

    @Test
    void
            handle_when_the_amount_is_a_statistical_outlier_against_the_accounts_history_then_sends_an_alert_email()
                    throws Exception {
        when(transactionRepository.findRecentWithdrawalAmounts("account-1", "transaction-1", 30))
                .thenReturn(List.of(10000L, 12000L, 9000L, 11000L, 10500L, 9500L));

        handler.handle(eventPayload(5_000_000));

        verify(transactionRepository).findRecentWithdrawalAmounts("account-1", "transaction-1", 30);
        verify(notificationService)
                .sendEmail(
                        eq("account-1"),
                        eq("WithdrawalAnomalyDetected"),
                        eq("owner-1@example.com"),
                        anyString(),
                        anyString());
    }

    @Test
    void handle_when_the_amount_is_within_the_accounts_normal_range_then_sends_no_alert()
            throws Exception {
        when(transactionRepository.findRecentWithdrawalAmounts(any(), any(), eq(30)))
                .thenReturn(List.of(4_900_000L, 5_100_000L, 4_950_000L, 5_050_000L, 5_000_000L));

        handler.handle(eventPayload(5_000_000));

        verify(notificationService, never()).sendEmail(any(), any(), any(), any(), any());
    }

    @Test
    void
            handle_when_the_account_has_fewer_than_5_prior_withdrawals_then_sends_no_alert_regardless_of_amount()
                    throws Exception {
        when(transactionRepository.findRecentWithdrawalAmounts(any(), any(), eq(30)))
                .thenReturn(List.of(10000L, 12000L));

        handler.handle(eventPayload(5_000_000));

        verify(notificationService, never()).sendEmail(any(), any(), any(), any(), any());
    }
}
