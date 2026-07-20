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
            logger.error("정지 알림 발송 실패", e)
        }
    }

    private fun sendEmail() {}
}
