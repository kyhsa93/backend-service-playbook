package com.example.accountservice.account.domain;

/**
 * The fixed taxonomy {@code TransactionAutoCategorizer} classifies a withdrawal's merchantName
 * into. Lives here (not in the application layer) for the same reason {@code TransactionType} does
 * — it's a value the domain read/write model carries.
 */
public enum TransactionCategory {
    FOOD,
    TRANSPORT,
    SHOPPING,
    HOUSING,
    MEDICAL,
    ENTERTAINMENT,
    UTILITIES,
    OTHER
}
