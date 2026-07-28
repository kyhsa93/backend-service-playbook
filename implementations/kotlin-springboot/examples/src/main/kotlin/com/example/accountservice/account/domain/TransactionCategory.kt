package com.example.accountservice.account.domain

// The fixed taxonomy TransactionAutoCategorizer classifies a withdrawal's merchantName into. Lives
// here (not in the application layer) for the same reason TransactionType does — it's a value the
// domain read/write model carries.
enum class TransactionCategory { FOOD, TRANSPORT, SHOPPING, HOUSING, MEDICAL, ENTERTAINMENT, UTILITIES, OTHER }
