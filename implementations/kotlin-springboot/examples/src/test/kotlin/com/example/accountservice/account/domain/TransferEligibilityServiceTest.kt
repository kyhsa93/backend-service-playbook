package com.example.accountservice.account.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

// TransferEligibilityService는 Account 어느 한쪽 인스턴스도 혼자서는 판단할 수 없는 규칙(동일
// 계좌 여부 + 두 계좌 활성 상태 + 통화 일치 + 잔액 충분)을 조율하는 Domain Service다 —
// 프레임워크 어노테이션이 없으므로 Spring 컨텍스트 없이 직접 인스턴스화해 판단 로직만 검증한다.
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
    fun `모든 조건을 만족하면 승인된다`() {
        val source = fundedAccount(amount = 10000)
        val target = fundedAccount(amount = 0)

        val decision = service.evaluate(source, target, 5000)

        assertThat(decision.approved).isTrue()
        assertThat(decision.error).isNull()
    }

    @Test
    fun `출금 계좌와 입금 계좌가 같으면 거부된다`() {
        val source = fundedAccount(amount = 10000)

        val decision = service.evaluate(source, source, 5000)

        assertThat(decision.approved).isFalse()
        assertThat(decision.error).isInstanceOf(TransferSameAccountException::class.java)
    }

    @Test
    fun `출금 계좌가 비활성이면 거부된다`() {
        val source = fundedAccount(amount = 10000)
        source.suspend()
        val target = fundedAccount(amount = 0)

        val decision = service.evaluate(source, target, 5000)

        assertThat(decision.approved).isFalse()
        assertThat(decision.error).isInstanceOf(WithdrawRequiresActiveAccountException::class.java)
    }

    @Test
    fun `입금 계좌가 비활성이면 거부된다`() {
        val source = fundedAccount(amount = 10000)
        val target = fundedAccount(amount = 0)
        target.suspend()

        val decision = service.evaluate(source, target, 5000)

        assertThat(decision.approved).isFalse()
        assertThat(decision.error).isInstanceOf(DepositRequiresActiveAccountException::class.java)
    }

    @Test
    fun `통화가 일치하지 않으면 거부된다`() {
        val source = fundedAccount(currency = "KRW", amount = 10000)
        val target = fundedAccount(currency = "USD", amount = 0)

        val decision = service.evaluate(source, target, 5000)

        assertThat(decision.approved).isFalse()
        assertThat(decision.error).isInstanceOf(CurrencyMismatchException::class.java)
    }

    @Test
    fun `출금 계좌 잔액이 부족하면 거부된다`() {
        val source = fundedAccount(amount = 1000)
        val target = fundedAccount(amount = 0)

        val decision = service.evaluate(source, target, 5000)

        assertThat(decision.approved).isFalse()
        assertThat(decision.error).isInstanceOf(InsufficientBalanceException::class.java)
    }
}
