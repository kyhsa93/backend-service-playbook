package com.example.accountservice.account.application.event

import com.example.accountservice.account.application.service.TransactionAutoCategorizer
import com.example.accountservice.account.domain.MoneyWithdrawnEvent
import com.example.accountservice.account.domain.TransactionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Reacts to [MoneyWithdrawnEvent] (registered in [com.example.accountservice.outbox.EventHandlerRegistry]
 * alongside [MoneyWithdrawnEventHandler] — the registry supports multiple subscribers per event type)
 * to categorize the transaction's merchantName asynchronously, off the money-movement hot path — the
 * same reasoning WithdrawService never calls an LLM directly. Inherently Level-1 idempotent (see
 * docs/architecture/domain-events.md): a retried delivery just re-runs the same find→categorize→save
 * cycle, landing on the same (or an equally acceptable) category.
 */
@Component
class CategorizeTransactionEventHandler(
    private val transactionAutoCategorizer: TransactionAutoCategorizer,
    private val transactionRepository: TransactionRepository,
) {
    private val logger = LoggerFactory.getLogger(CategorizeTransactionEventHandler::class.java)

    fun handle(event: MoneyWithdrawnEvent) {
        // Nothing to classify — the requester didn't attach a merchantName to this withdrawal.
        val merchantName = event.merchantName
        if (merchantName.isNullOrBlank()) return

        val transaction = transactionRepository.findTransaction(event.transactionId) ?: return

        val category = transactionAutoCategorizer.categorize(merchantName, event.amount.amount)
        transactionRepository.saveTransaction(transaction.categorize(category))
        logger.info("Transaction categorized transaction_id={} category={}", event.transactionId, category)
    }
}
