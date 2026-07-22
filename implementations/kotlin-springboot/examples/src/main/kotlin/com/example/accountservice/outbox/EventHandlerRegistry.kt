package com.example.accountservice.outbox

import com.example.accountservice.account.application.event.AccountClosedEventHandler
import com.example.accountservice.account.application.event.AccountCreatedEventHandler
import com.example.accountservice.account.application.event.AccountReactivatedEventHandler
import com.example.accountservice.account.application.event.AccountSuspendedEventHandler
import com.example.accountservice.account.application.event.InterestPaidEventHandler
import com.example.accountservice.account.application.event.MoneyDepositedEventHandler
import com.example.accountservice.account.application.event.MoneyWithdrawnEventHandler
import com.example.accountservice.account.application.integrationevent.AccountClosedIntegrationEventV1
import com.example.accountservice.account.application.integrationevent.AccountSuspendedIntegrationEventV1
import com.example.accountservice.account.domain.AccountClosedEvent
import com.example.accountservice.account.domain.AccountCreatedEvent
import com.example.accountservice.account.domain.AccountReactivatedEvent
import com.example.accountservice.account.domain.AccountSuspendedEvent
import com.example.accountservice.account.domain.InterestPaidEvent
import com.example.accountservice.account.domain.MoneyDepositedEvent
import com.example.accountservice.account.domain.MoneyWithdrawnEvent
import com.example.accountservice.account.interfaces.integrationevent.AccountIntegrationEventController
import com.example.accountservice.card.interfaces.integrationevent.CardIntegrationEventController
import com.example.accountservice.payment.application.event.PaymentCancelledEventHandler
import com.example.accountservice.payment.application.event.PaymentCompletedEventHandler
import com.example.accountservice.payment.application.event.RefundApprovedEventHandler
import com.example.accountservice.payment.application.integrationevent.PaymentCancelledIntegrationEventV1
import com.example.accountservice.payment.application.integrationevent.PaymentCompletedIntegrationEventV1
import com.example.accountservice.payment.application.integrationevent.RefundApprovedIntegrationEventV1
import com.example.accountservice.payment.domain.PaymentCancelledEvent
import com.example.accountservice.payment.domain.PaymentCompletedEvent
import com.example.accountservice.payment.domain.RefundApprovedEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Maps eventType (the Outbox row's `eventType` column = SQS `MessageAttributes.eventType`) to a handler
 * function. [OutboxConsumer] routes to this registry whenever it receives a message from SQS — whether
 * it's a Domain Event Handler (`application/event/`) or an Integration Event Controller
 * (`interfaces/integrationevent/`), it goes through this single registry.
 *
 * Rather than evaluating the routing table with a `when` expression on every dispatch, this registry
 * builds the entire `Map<eventType, (eventId, payload) -> Unit>` once at construction time (after Spring
 * has already auto-collected and injected each Handler/Controller as a `@Component`). This differs from
 * nestjs's `EventHandlerRegistry.register()`, where each domain module populates the registry separately
 * in its own `onModuleInit()` — this repository has no per-domain module-registration step (it's a
 * single Spring context), so there's no need to split it that way; this one registry always knows about
 * every Account/Payment domain handler in a single place.
 *
 * Account's 7 Domain Event Handlers receive the Outbox row's `eventId` as-is — this value is used as
 * `NotificationService`'s Level 2 (Ledger) duplicate-send-prevention key (`sourceEventId`)
 * (domain-events.md). The Payment/Refund Domain Event Handlers and the Integration Event Controller
 * don't use `eventId`, so the lambda ignores it.
 */
@Component
class EventHandlerRegistry(
    private val objectMapper: ObjectMapper,
    private val accountCreatedEventHandler: AccountCreatedEventHandler,
    private val moneyDepositedEventHandler: MoneyDepositedEventHandler,
    private val moneyWithdrawnEventHandler: MoneyWithdrawnEventHandler,
    private val accountSuspendedEventHandler: AccountSuspendedEventHandler,
    private val accountReactivatedEventHandler: AccountReactivatedEventHandler,
    private val accountClosedEventHandler: AccountClosedEventHandler,
    private val interestPaidEventHandler: InterestPaidEventHandler,
    // The receiving side for Card BC's Integration Events. Since outbox/ is shared infrastructure that
    // doesn't belong to any BC, this file referencing Card is not itself a violation of the rule
    // (the constraint is only that Account must not reference Card).
    private val cardIntegrationEventController: CardIntegrationEventController,
    // The handler that converts Payment/Refund Domain Events into Integration Events.
    private val paymentCompletedEventHandler: PaymentCompletedEventHandler,
    private val paymentCancelledEventHandler: PaymentCancelledEventHandler,
    private val refundApprovedEventHandler: RefundApprovedEventHandler,
    // The receiving side for Payment BC's Integration Events — Account subscribes to Payment's
    // payment.completed.v1/payment.cancelled.v1/refund.approved.v1 to perform the actual
    // debit/compensating credit.
    private val accountIntegrationEventController: AccountIntegrationEventController,
) {
    private val logger = LoggerFactory.getLogger(EventHandlerRegistry::class.java)

    private val handlers: Map<String, (eventId: String, payload: String) -> Unit> =
        mapOf(
            "AccountCreatedEvent" to { eventId, payload ->
                accountCreatedEventHandler.handle(objectMapper.readValue(payload, AccountCreatedEvent::class.java), eventId)
            },
            "MoneyDepositedEvent" to { eventId, payload ->
                moneyDepositedEventHandler.handle(objectMapper.readValue(payload, MoneyDepositedEvent::class.java), eventId)
            },
            "MoneyWithdrawnEvent" to { eventId, payload ->
                moneyWithdrawnEventHandler.handle(objectMapper.readValue(payload, MoneyWithdrawnEvent::class.java), eventId)
            },
            "AccountSuspendedEvent" to { eventId, payload ->
                accountSuspendedEventHandler.handle(objectMapper.readValue(payload, AccountSuspendedEvent::class.java), eventId)
            },
            "AccountReactivatedEvent" to { eventId, payload ->
                accountReactivatedEventHandler.handle(objectMapper.readValue(payload, AccountReactivatedEvent::class.java), eventId)
            },
            "AccountClosedEvent" to { eventId, payload ->
                accountClosedEventHandler.handle(objectMapper.readValue(payload, AccountClosedEvent::class.java), eventId)
            },
            "InterestPaidEvent" to { eventId, payload ->
                interestPaidEventHandler.handle(objectMapper.readValue(payload, InterestPaidEvent::class.java), eventId)
            },
            AccountSuspendedIntegrationEventV1.EVENT_NAME to { _, payload ->
                val event = objectMapper.readValue(payload, AccountSuspendedIntegrationEventV1::class.java)
                cardIntegrationEventController.onAccountSuspended(event.accountId)
            },
            AccountClosedIntegrationEventV1.EVENT_NAME to { _, payload ->
                val event = objectMapper.readValue(payload, AccountClosedIntegrationEventV1::class.java)
                cardIntegrationEventController.onAccountClosed(event.accountId)
            },
            "PaymentCompletedEvent" to { _, payload ->
                paymentCompletedEventHandler.handle(objectMapper.readValue(payload, PaymentCompletedEvent::class.java))
            },
            "PaymentCancelledEvent" to { _, payload ->
                paymentCancelledEventHandler.handle(objectMapper.readValue(payload, PaymentCancelledEvent::class.java))
            },
            "RefundApprovedEvent" to { _, payload ->
                refundApprovedEventHandler.handle(objectMapper.readValue(payload, RefundApprovedEvent::class.java))
            },
            PaymentCompletedIntegrationEventV1.EVENT_NAME to { _, payload ->
                val event = objectMapper.readValue(payload, PaymentCompletedIntegrationEventV1::class.java)
                accountIntegrationEventController.onPaymentCompleted(event.paymentId, event.accountId, event.amount)
            },
            PaymentCancelledIntegrationEventV1.EVENT_NAME to { _, payload ->
                val event = objectMapper.readValue(payload, PaymentCancelledIntegrationEventV1::class.java)
                accountIntegrationEventController.onPaymentCancelled(event.paymentId, event.accountId, event.amount)
            },
            RefundApprovedIntegrationEventV1.EVENT_NAME to { _, payload ->
                val event = objectMapper.readValue(payload, RefundApprovedIntegrationEventV1::class.java)
                accountIntegrationEventController.onRefundApproved(event.refundId, event.accountId, event.amount)
            },
        )

    /** The set of registered eventTypes — for diagnostics/testing (checking which events can be
     * routed). */
    fun registeredEventTypes(): Set<String> = handlers.keys

    /**
     * Called every time [OutboxConsumer] receives one SQS message. If no handler is registered, it just
     * logs a warning and returns quietly (it doesn't throw — there's no reason to retry an unknown
     * event type forever). If the handler throws, the exception propagates as-is so [OutboxConsumer]
     * doesn't delete the message and instead leaves it to SQS's redelivery (at-least-once).
     */
    fun dispatch(
        eventType: String,
        eventId: String,
        payload: String,
    ) {
        val handler = handlers[eventType]
        if (handler == null) {
            logger.warn("Unknown event type: {}", eventType)
            return
        }
        handler(eventId, payload)
    }
}
