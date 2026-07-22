package com.example.accountservice.payment.application.command

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
    private val service = CreatePaymentService(paymentRepository, cardAdapter, accountAdapter)

    @Test
    fun `when the card is active and the balance is sufficient, the payment completes immediately and is saved`() {
        every { cardAdapter.findCard("card-1", "owner-1") } returns
            CardView(cardId = "card-1", accountId = "account-1", active = true)
        every { accountAdapter.findAccount("account-1", "owner-1") } returns
            AccountView(accountId = "account-1", active = true, balanceAmount = 1000, currency = "KRW")

        val result = service.create(CreatePaymentCommand(cardId = "card-1", amount = 500, requesterId = "owner-1"))

        assertThat(result.status).isEqualTo(PaymentStatus.COMPLETED.name)
        assertThat(result.accountId).isEqualTo("account-1")
        verify(exactly = 1) { paymentRepository.savePayment(any()) }
    }

    @Test
    fun `throws an exception when the card to link cannot be found`() {
        every { cardAdapter.findCard("card-1", "owner-1") } returns null

        assertThrows<LinkedCardNotFoundException> {
            service.create(CreatePaymentCommand(cardId = "card-1", amount = 500, requesterId = "owner-1"))
        }
        verify(exactly = 0) { paymentRepository.savePayment(any()) }
    }

    @Test
    fun `throws an exception when the card is inactive`() {
        every { cardAdapter.findCard("card-1", "owner-1") } returns
            CardView(cardId = "card-1", accountId = "account-1", active = false)

        assertThrows<PaymentRequiresActiveCardException> {
            service.create(CreatePaymentCommand(cardId = "card-1", amount = 500, requesterId = "owner-1"))
        }
        verify(exactly = 0) { paymentRepository.savePayment(any()) }
    }

    @Test
    fun `throws an exception when the linked account cannot be found`() {
        every { cardAdapter.findCard("card-1", "owner-1") } returns
            CardView(cardId = "card-1", accountId = "account-1", active = true)
        every { accountAdapter.findAccount("account-1", "owner-1") } returns null

        assertThrows<LinkedAccountNotFoundException> {
            service.create(CreatePaymentCommand(cardId = "card-1", amount = 500, requesterId = "owner-1"))
        }
    }

    @Test
    fun `throws an exception when the account is inactive`() {
        every { cardAdapter.findCard("card-1", "owner-1") } returns
            CardView(cardId = "card-1", accountId = "account-1", active = true)
        every { accountAdapter.findAccount("account-1", "owner-1") } returns
            AccountView(accountId = "account-1", active = false, balanceAmount = 1000, currency = "KRW")

        assertThrows<PaymentRequiresActiveAccountException> {
            service.create(CreatePaymentCommand(cardId = "card-1", amount = 500, requesterId = "owner-1"))
        }
    }

    @Test
    fun `throws an exception when the balance is insufficient`() {
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
