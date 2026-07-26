package com.example.accountservice.account.application.service;

import com.example.accountservice.account.domain.TransactionType;
import java.time.LocalDate;

/**
 * A plain, narrow shape — only the fields that can safely narrow WHAT is returned. Deliberately has
 * no {@code accountId}/{@code ownerId} field: {@code AskTransactionHistoryService} (the Application
 * layer caller) always scopes the lookup to the authenticated requester's own account, and never
 * lets a value derived from the LLM's interpretation of free text influence WHO the data belongs
 * to.
 */
public record TransactionFilter(TransactionType type, LocalDate fromDate, LocalDate toDate) {}
