package com.example.accountservice.account.application.command

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class CleanupService {
    @Scheduled(fixedDelay = 1000)
    fun cleanup() {
        // ...
    }
}
