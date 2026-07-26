package com.example.accountservice.account.application.service

import com.example.accountservice.account.domain.TransactionType
import java.time.LocalDate

/**
 * A plain, narrow shape — only the fields that can safely narrow WHAT is returned. Deliberately has
 * no `accountId`/`ownerId` field: [AskTransactionHistoryService][com.example.accountservice.account.application.query.AskTransactionHistoryService]
 * (the Application layer caller) always scopes the lookup to the authenticated requester's own
 * account, and never lets a value derived from the LLM's interpretation of free text influence WHO
 * the data belongs to.
 */
data class TransactionFilter(
    val type: TransactionType? = null,
    val fromDate: LocalDate? = null,
    val toDate: LocalDate? = null,
)
