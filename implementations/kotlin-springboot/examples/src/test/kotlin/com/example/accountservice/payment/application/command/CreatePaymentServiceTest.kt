package com.example.accountservice.payment.application.command

import com.example.accountservice.outbox.OutboxRelay
import com.example.accountservice.payment.application.adapter.AccountAdapter
import com.example.accountservice.payment.application.adapter.AccountView
import com.example.accountservice.payment.application.adapter.CardAdapter
import com.example.accountservice.payment.application.adapter.CardView
import com.example.accountservice.payment.domain.InsufficientBalanceException
import com.example.accountservice.payment.domain.LinkedAccountNotFoundException
import com.example.accountservice.payment.domain.LinkedCardNotFoundException
import com.example.accountservice.payment.domain.PaymentRepository
import com.example.accountservice.payment.domain.PaymentRequiresActiveAccountException
import com.example.accountservice.payment.domain.PaymentRequiresActiveCardException
import com.example.accountservice.payment.domain.PaymentStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CreatePaymentServiceTest {
    private val paymentRepository = mockk<PaymentRepository>(relaxed = true)
    private val cardAdapter = mockk<CardAdapter>()
    private val accountAdapter = mockk<AccountAdapter>()
    private val outboxRelay = mockk<OutboxRelay>(relaxed = true)
    private val service = CreatePaymentService(paymentRepository, cardAdapter, accountAdapter, outboxRelay)

    @Test
    fun `카드가 활성이고 잔액이 충분하면 결제가 즉시 완료되고 저장 및 Outbox 드레인이 일어난다`() {
        every { cardAdapter.findCard("card-1", "owner-1") } returns
            CardView(cardId = "card-1", accountId = "account-1", active = true)
        every { accountAdapter.findAccount("account-1", "owner-1") } returns
            AccountView(accountId = "account-1", active = true, balanceAmount = 1000, currency = "KRW")

        val result = service.create(CreatePaymentCommand(cardId = "card-1", amount = 500, requesterId = "owner-1"))

        assertThat(result.status).isEqualTo(PaymentStatus.COMPLETED.name)
        assertThat(result.accountId).isEqualTo("account-1")
        verify(exactly = 1) { paymentRepository.savePayment(any()) }
        verify(exactly = 1) { outboxRelay.processPending() }
    }

    @Test
    fun `연결할 카드를 찾을 수 없으면 예외를 던진다`() {
        every { cardAdapter.findCard("card-1", "owner-1") } returns null

        assertThrows<LinkedCardNotFoundException> {
            service.create(CreatePaymentCommand(cardId = "card-1", amount = 500, requesterId = "owner-1"))
        }
        verify(exactly = 0) { paymentRepository.savePayment(any()) }
    }

    @Test
    fun `카드가 비활성 상태면 예외를 던진다`() {
        every { cardAdapter.findCard("card-1", "owner-1") } returns
            CardView(cardId = "card-1", accountId = "account-1", active = false)

        assertThrows<PaymentRequiresActiveCardException> {
            service.create(CreatePaymentCommand(cardId = "card-1", amount = 500, requesterId = "owner-1"))
        }
        verify(exactly = 0) { paymentRepository.savePayment(any()) }
    }

    @Test
    fun `연결된 계좌를 찾을 수 없으면 예외를 던진다`() {
        every { cardAdapter.findCard("card-1", "owner-1") } returns
            CardView(cardId = "card-1", accountId = "account-1", active = true)
        every { accountAdapter.findAccount("account-1", "owner-1") } returns null

        assertThrows<LinkedAccountNotFoundException> {
            service.create(CreatePaymentCommand(cardId = "card-1", amount = 500, requesterId = "owner-1"))
        }
    }

    @Test
    fun `계좌가 비활성 상태면 예외를 던진다`() {
        every { cardAdapter.findCard("card-1", "owner-1") } returns
            CardView(cardId = "card-1", accountId = "account-1", active = true)
        every { accountAdapter.findAccount("account-1", "owner-1") } returns
            AccountView(accountId = "account-1", active = false, balanceAmount = 1000, currency = "KRW")

        assertThrows<PaymentRequiresActiveAccountException> {
            service.create(CreatePaymentCommand(cardId = "card-1", amount = 500, requesterId = "owner-1"))
        }
    }

    @Test
    fun `잔액이 부족하면 예외를 던진다`() {
        every { cardAdapter.findCard("card-1", "owner-1") } returns
            CardView(cardId = "card-1", accountId = "account-1", active = true)
        every { accountAdapter.findAccount("account-1", "owner-1") } returns
            AccountView(accountId = "account-1", active = true, balanceAmount = 100, currency = "KRW")

        assertThrows<InsufficientBalanceException> {
            service.create(CreatePaymentCommand(cardId = "card-1", amount = 500, requesterId = "owner-1"))
        }
        verify(exactly = 0) { paymentRepository.savePayment(any()) }
    }
}
