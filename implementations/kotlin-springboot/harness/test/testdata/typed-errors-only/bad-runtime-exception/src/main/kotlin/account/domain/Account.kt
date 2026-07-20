package com.example.accountservice.account.domain

class Account {
    fun deposit(amount: Long) {
        if (amount <= 0) throw RuntimeException("금액은 0보다 커야 합니다.")
    }
}
