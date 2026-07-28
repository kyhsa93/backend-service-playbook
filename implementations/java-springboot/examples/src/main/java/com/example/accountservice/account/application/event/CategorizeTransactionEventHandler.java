package com.example.accountservice.account.application.event;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.account.application.service.TransactionAutoCategorizer;
import com.example.accountservice.account.domain.MoneyWithdrawnEvent;
import com.example.accountservice.account.domain.Transaction;
import com.example.accountservice.account.domain.TransactionCategory;
import com.example.accountservice.account.domain.TransactionRepository;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reacts to {@link MoneyWithdrawnEvent} (registered alongside {@code MoneyWithdrawnEventHandler} —
 * {@code OutboxConsumer}/{@code OutboxEventDispatcher} support multiple subscribers per event type)
 * to categorize the transaction's merchantName asynchronously, off the money-movement hot path —
 * the same reasoning {@code WithdrawService} never calls an LLM directly. Inherently Level-1
 * idempotent (see docs/architecture/domain-events.md): a retried delivery just re-runs the same
 * find→categorize→save cycle, landing on the same (or an equally acceptable) category.
 */
@Component
@RequiredArgsConstructor
public class CategorizeTransactionEventHandler implements OutboxEventHandler {

    private static final Logger log =
            LoggerFactory.getLogger(CategorizeTransactionEventHandler.class);

    private final TransactionAutoCategorizer transactionAutoCategorizer;
    private final TransactionRepository transactionRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return MoneyWithdrawnEvent.class.getSimpleName();
    }

    @Override
    public void handle(String payload) throws Exception {
        MoneyWithdrawnEvent event = objectMapper.readValue(payload, MoneyWithdrawnEvent.class);
        // Nothing to classify — the requester didn't attach a merchantName to this withdrawal.
        if (event.merchantName() == null || event.merchantName().isBlank()) {
            return;
        }

        Transaction transaction = transactionRepository.findTransaction(event.transactionId());
        if (transaction == null) {
            return;
        }

        TransactionCategory category =
                transactionAutoCategorizer.categorize(
                        event.merchantName(), event.amount().amount());
        transactionRepository.saveTransaction(transaction.categorize(category));
        log.info(
                "Transaction categorized",
                kv("transaction_id", event.transactionId()),
                kv("category", category));
    }
}
