package com.example.accountservice.account.domain

class Account {
    fun deposit(amount: Long) {
        if (amount <= 0) throw RuntimeException("Amount must be greater than 0.")
    }
}
