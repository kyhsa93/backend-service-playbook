package com.example.accountservice.outbox

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class OutboxPoller {
    @Scheduled(fixedDelay = 1000)
    fun poll() {
        // ...
    }
}
