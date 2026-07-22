package com.example.accountservice.card.interfaces.integrationevent

import com.example.accountservice.card.application.command.CancelCardsByAccountService
import com.example.accountservice.card.application.command.SuspendCardsByAccountService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The Interface input adapter that receives Integration Events published by an external BC (Account).
 * This is an input boundary at the same location (interfaces/) as an HTTP Controller — it calls only
 * its own domain's use cases (Command Services), and lets any exception propagate so
 * [com.example.accountservice.outbox.OutboxConsumer] can handle retries (the message is not deleted,
 * so it is redelivered after the SQS visibility timeout).
 *
 * This component is injected into the [com.example.accountservice.outbox.EventHandlerRegistry]
 * constructor and called from the `account.suspended.v1`/`account.closed.v1` routing entries. Since
 * only the accountId is extracted from Account's public contract (payload) and passed in, Card has no
 * dependency on Account's Integration Event classes.
 */
@Component
class CardIntegrationEventController(
    private val suspendCardsByAccountService: SuspendCardsByAccountService,
    private val cancelCardsByAccountService: CancelCardsByAccountService,
) {
    private val logger = LoggerFactory.getLogger(CardIntegrationEventController::class.java)

    fun onAccountSuspended(accountId: String) {
        logger.atInfo().addKeyValue("account_id", accountId).log("Received account.suspended.v1")
        suspendCardsByAccountService.suspend(accountId)
    }

    fun onAccountClosed(accountId: String) {
        logger.atInfo().addKeyValue("account_id", accountId).log("Received account.closed.v1")
        cancelCardsByAccountService.cancel(accountId)
    }
}
