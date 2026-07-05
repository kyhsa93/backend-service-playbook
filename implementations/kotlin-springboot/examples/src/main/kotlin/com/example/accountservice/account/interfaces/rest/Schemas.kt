package com.example.accountservice.account.interfaces.rest

data class CreateAccountRequest(val currency: String)

data class DepositRequest(val amount: Long)

data class WithdrawRequest(val amount: Long)

data class ErrorResponse(val message: String)
