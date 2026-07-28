package com.example.accountservice.account.application.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accountservice.account.application.service.TransactionAutoCategorizer;
import com.example.accountservice.account.domain.Money;
import com.example.accountservice.account.domain.Transaction;
import com.example.accountservice.account.domain.TransactionCategory;
import com.example.accountservice.account.domain.TransactionRepository;
import com.example.accountservice.account.domain.TransactionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CategorizeTransactionEventHandlerTest {

    @Mock private TransactionAutoCategorizer transactionAutoCategorizer;
    @Mock private TransactionRepository transactionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private CategorizeTransactionEventHandler handler;

    private final Transaction transaction =
            Transaction.reconstitute(
                    "transaction-1",
                    "account-1",
                    TransactionType.WITHDRAWAL,
                    new Money(5500, "KRW"),
                    null,
                    "Starbucks Gangnam",
                    null,
                    LocalDateTime.now());

    @BeforeEach
    void setUp() {
        handler =
                new CategorizeTransactionEventHandler(
                        transactionAutoCategorizer, transactionRepository, objectMapper);
    }

    private String eventPayload(String merchantName) throws Exception {
        Map<String, Object> event = new HashMap<>();
        event.put("accountId", "account-1");
        event.put("email", "owner-1@example.com");
        event.put("transactionId", "transaction-1");
        event.put("amount", Map.of("amount", 5500, "currency", "KRW"));
        event.put("balanceAfter", Map.of("amount", 4500, "currency", "KRW"));
        event.put("merchantName", merchantName);
        event.put("createdAt", LocalDateTime.now().toString());
        return objectMapper.writeValueAsString(event);
    }

    @Test
    void handle_when_the_event_has_a_merchantName_then_categorizes_and_saves_it() throws Exception {
        when(transactionRepository.findTransaction("transaction-1")).thenReturn(transaction);
        when(transactionAutoCategorizer.categorize("Starbucks Gangnam", 5500))
                .thenReturn(TransactionCategory.FOOD);

        handler.handle(eventPayload("Starbucks Gangnam"));

        verify(transactionAutoCategorizer).categorize("Starbucks Gangnam", 5500);
        verify(transactionRepository)
                .saveTransaction(argThat(t -> t.getCategory() == TransactionCategory.FOOD));
    }

    @Test
    void handle_when_the_event_has_no_merchantName_then_skips_categorization_entirely()
            throws Exception {
        handler.handle(eventPayload(null));

        verify(transactionRepository, never()).findTransaction(any());
        verify(transactionAutoCategorizer, never()).categorize(any(), anyLong());
        verify(transactionRepository, never()).saveTransaction(any());
    }

    @Test
    void handle_when_the_transaction_no_longer_exists_then_skips_categorization_without_throwing()
            throws Exception {
        when(transactionRepository.findTransaction("transaction-1")).thenReturn(null);

        handler.handle(eventPayload("Starbucks Gangnam"));

        verify(transactionAutoCategorizer, never()).categorize(any(), anyLong());
        verify(transactionRepository, never()).saveTransaction(any());
    }
}
