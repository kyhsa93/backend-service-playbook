package com.example.accountservice.account.application.event

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class AccountSuspendedEventHandler {
    private val logger = LoggerFactory.getLogger(AccountSuspendedEventHandler::class.java)

    fun handle() {
        try {
            sendEmail()
        } catch (e: Exception) {
            logger.error("failed to send suspension notification", e)
        }
    }

    private fun sendEmail() {}
}
