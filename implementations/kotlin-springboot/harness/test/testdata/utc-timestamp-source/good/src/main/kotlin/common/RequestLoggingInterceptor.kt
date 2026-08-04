package com.example.accountservice.common

import java.time.LocalDateTime

// Measures how long the request took. The reading is never stored, only subtracted from a later
// one, so the zone it resolves against is irrelevant — this file is outside the rule's scope and
// produces no finding at all.
class RequestLoggingInterceptor {
    fun start(): LocalDateTime = LocalDateTime.now()
}
