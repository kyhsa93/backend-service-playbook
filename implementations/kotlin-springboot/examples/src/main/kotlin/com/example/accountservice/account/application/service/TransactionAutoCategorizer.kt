package com.example.accountservice.account.application.service

import com.example.accountservice.account.domain.TransactionCategory

/**
 * A Technical Service (see root docs/architecture/domain-service.md) wrapping a self-hosted LLM
 * call — the same placement/shape as [NlTransactionQueryTranslator], just classifying a
 * merchantName + amount into a fixed category instead of translating a question into a filter.
 */
interface TransactionAutoCategorizer {
    fun categorize(
        merchantName: String,
        amount: Long,
    ): TransactionCategory
}
