package com.example.accountservice.common

import java.time.LocalDateTime

class RequestLoggingInterceptor {
    fun start(): LocalDateTime = LocalDateTime.now()
}
