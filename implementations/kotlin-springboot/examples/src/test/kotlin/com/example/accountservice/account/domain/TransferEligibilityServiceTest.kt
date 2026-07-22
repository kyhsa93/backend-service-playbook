package com.example.accountservice.account.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

// TransferEligibilityService is a Domain Service that coordinates a rule (same-account check +
// both accounts' active status + currency match + sufficient balance) that neither Account instance
// alone can decide — since it has no framework annotations, it is instantiated directly without a
// Spring context to verify only the decision logic.
class TransferEligibilityServiceTest {
    private val service = TransferEligibilityService()

    private fun fundedAccount(
        currency: String = "KRW",
        amount: Long = 0,
    ): Account {
        val account = Account.create("owner-1", currency, "owner-1@example.com")
        if (amount > 0) account.deposit(amount)
        return account
    }

    @Test
    fun `is approved when all conditions are satisfied`() {
        val source = fundedAccount(amount = 10000)
        val target = fundedAccount(amount = 0)

        val decision = service.evaluate(source, target, 5000)

        assertThat(decision.approved).isTrue()
        assertThat(decision.error).isNull()
    }

    @Test
    fun `is rejected when the withdrawal account and deposit account are the same`() {
        val source = fundedAccount(amount = 10000)

        val decision = service.evaluate(source, source, 5000)

        assertThat(decision.approved).isFalse()
        assertThat(decision.error).isInstanceOf(TransferSameAccountException::class.java)
    }

    @Test
    fun `is rejected when the withdrawal account is inactive`() {
        val source = fundedAccount(amount = 10000)
        source.suspend()
        val target = fundedAccount(amount = 0)

        val decision = service.evaluate(source, target, 5000)

        assertThat(decision.approved).isFalse()
        assertThat(decision.error).isInstanceOf(WithdrawRequiresActiveAccountException::class.java)
    }

    @Test
    fun `is rejected when the deposit account is inactive`() {
        val source = fundedAccount(amount = 10000)
        val target = fundedAccount(amount = 0)
        target.suspend()

        val decision = service.evaluate(source, target, 5000)

        assertThat(decision.approved).isFalse()
        assertThat(decision.error).isInstanceOf(DepositRequiresActiveAccountException::class.java)
    }

    @Test
    fun `is rejected when the currencies do not match`() {
        val source = fundedAccount(currency = "KRW", amount = 10000)
        val target = fundedAccount(currency = "USD", amount = 0)

        val decision = service.evaluate(source, target, 5000)

        assertThat(decision.approved).isFalse()
        assertThat(decision.error).isInstanceOf(CurrencyMismatchException::class.java)
    }

    @Test
    fun `is rejected when the withdrawal account has insufficient balance`() {
        val source = fundedAccount(amount = 1000)
        val target = fundedAccount(amount = 0)

        val decision = service.evaluate(source, target, 5000)

        assertThat(decision.approved).isFalse()
        assertThat(decision.error).isInstanceOf(InsufficientBalanceException::class.java)
    }
}
