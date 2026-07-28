package com.example.accountservice.outbox

import com.example.accountservice.account.application.event.AccountClosedEventHandler
import com.example.accountservice.account.application.event.AccountCreatedEventHandler
import com.example.accountservice.account.application.event.AccountReactivatedEventHandler
import com.example.accountservice.account.application.event.AccountSuspendedEventHandler
import com.example.accountservice.account.application.event.CategorizeTransactionEventHandler
import com.example.accountservice.account.application.event.DetectWithdrawalAnomalyEventHandler
import com.example.accountservice.account.application.event.InterestPaidEventHandler
import com.example.accountservice.account.application.event.MoneyDepositedEventHandler
import com.example.accountservice.account.application.event.MoneyWithdrawnEventHandler
import com.example.accountservice.account.domain.Money
import com.example.accountservice.account.domain.MoneyWithdrawnEvent
import com.example.accountservice.account.interfaces.integrationevent.AccountIntegrationEventController
import com.example.accountservice.card.interfaces.integrationevent.CardIntegrationEventController
import com.example.accountservice.payment.application.event.ClassifyRefundReasonEventHandler
import com.example.accountservice.payment.application.event.PaymentCancelledEventHandler
import com.example.accountservice.payment.application.event.PaymentCompletedEventHandler
import com.example.accountservice.payment.application.event.RefundApprovedEventHandler
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * MoneyWithdrawnEvent is this codebase's first eventType with more than one subscriber
 * (MoneyWithdrawnEventHandler for the notification email, CategorizeTransactionEventHandler for
 * spending categorization, and DetectWithdrawalAnomalyEventHandler for the anomaly alert — now 3
 * subscribers) — see the class doc on EventHandlerRegistry for why the routing table had to change
 * shape (Map<String, ...> -> Map<String, List<...>>) to support this without silently dropping any
 * of them.
 */
class EventHandlerRegistryTest {
    // findAndRegisterModules() picks up jackson-datatype-jsr310 (LocalDateTime support), and
    // FAIL_ON_UNKNOWN_PROPERTIES is disabled — both match Spring's auto-configured ObjectMapper
    // bean the production EventHandlerRegistry actually uses (Jackson2ObjectMapperBuilder disables
    // that feature by default, which is why a Value Object like Money's isZero() computed property
    // getter being picked up by Jackson's bean introspection doesn't break real event round-trips).
    private val objectMapper =
        jacksonObjectMapper().findAndRegisterModules().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    private val moneyWithdrawnEventHandler = mockk<MoneyWithdrawnEventHandler>(relaxed = true)
    private val categorizeTransactionEventHandler = mockk<CategorizeTransactionEventHandler>(relaxed = true)
    private val detectWithdrawalAnomalyEventHandler = mockk<DetectWithdrawalAnomalyEventHandler>(relaxed = true)
    private lateinit var registry: EventHandlerRegistry

    @BeforeEach
    fun setUp() {
        registry =
            EventHandlerRegistry(
                objectMapper = objectMapper,
                accountCreatedEventHandler = mockk<AccountCreatedEventHandler>(relaxed = true),
                moneyDepositedEventHandler = mockk<MoneyDepositedEventHandler>(relaxed = true),
                moneyWithdrawnEventHandler = moneyWithdrawnEventHandler,
                categorizeTransactionEventHandler = categorizeTransactionEventHandler,
                detectWithdrawalAnomalyEventHandler = detectWithdrawalAnomalyEventHandler,
                accountSuspendedEventHandler = mockk<AccountSuspendedEventHandler>(relaxed = true),
                accountReactivatedEventHandler = mockk<AccountReactivatedEventHandler>(relaxed = true),
                accountClosedEventHandler = mockk<AccountClosedEventHandler>(relaxed = true),
                interestPaidEventHandler = mockk<InterestPaidEventHandler>(relaxed = true),
                cardIntegrationEventController = mockk<CardIntegrationEventController>(relaxed = true),
                paymentCompletedEventHandler = mockk<PaymentCompletedEventHandler>(relaxed = true),
                paymentCancelledEventHandler = mockk<PaymentCancelledEventHandler>(relaxed = true),
                refundApprovedEventHandler = mockk<RefundApprovedEventHandler>(relaxed = true),
                classifyRefundReasonEventHandler = mockk<ClassifyRefundReasonEventHandler>(relaxed = true),
                accountIntegrationEventController = mockk<AccountIntegrationEventController>(relaxed = true),
            )
    }

    private fun payload(): String =
        objectMapper.writeValueAsString(
            MoneyWithdrawnEvent(
                accountId = "account-1",
                email = "owner-1@example.com",
                transactionId = "transaction-1",
                amount = Money(5500, "KRW"),
                balanceAfter = Money(4500, "KRW"),
                createdAt = LocalDateTime.now(),
                merchantName = "Starbucks Gangnam",
            ),
        )

    @Test
    fun `dispatching MoneyWithdrawnEvent calls every registered handler, not just the last one registered`() {
        registry.dispatch("MoneyWithdrawnEvent", "event-1", payload())

        verify(exactly = 1) { moneyWithdrawnEventHandler.handle(any(), "event-1") }
        verify(exactly = 1) { categorizeTransactionEventHandler.handle(any()) }
        verify(exactly = 1) { detectWithdrawalAnomalyEventHandler.handle(any(), "event-1") }
    }

    @Test
    fun `when one handler throws, the sibling handlers for the same eventType still run`() {
        every { moneyWithdrawnEventHandler.handle(any(), any()) } throws RuntimeException("boom")

        assertThatThrownBy { registry.dispatch("MoneyWithdrawnEvent", "event-1", payload()) }
            .hasMessageContaining("boom")

        verify(exactly = 1) { categorizeTransactionEventHandler.handle(any()) }
        verify(exactly = 1) { detectWithdrawalAnomalyEventHandler.handle(any(), "event-1") }
    }

    @Test
    fun `dispatching an unknown eventType logs and returns without throwing`() {
        registry.dispatch("SomeUnregisteredEvent", "event-1", "{}")

        verify(exactly = 0) { moneyWithdrawnEventHandler.handle(any(), any()) }
    }

    @Test
    fun `registeredEventTypes includes MoneyWithdrawnEvent exactly once despite having two handlers`() {
        assertThat(registry.registeredEventTypes()).contains("MoneyWithdrawnEvent")
    }
}
