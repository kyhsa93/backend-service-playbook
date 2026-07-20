package com.example.accountservice.account.application.event

import org.springframework.stereotype.Component

@Component
class AccountSuspendedEventHandler {
    fun handle() {
        try {
            sendEmail()
        } catch (e: Exception) {
        }
    }

    private fun sendEmail() {}
}
