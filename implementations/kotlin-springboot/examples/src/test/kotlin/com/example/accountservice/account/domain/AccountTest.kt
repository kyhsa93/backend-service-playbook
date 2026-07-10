package com.example.accountservice.account.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AccountTest {

    private fun createAccount(currency: String = "KRW"): Account =
        Account.create(ownerId = "owner-1", currency = currency, email = "owner-1@example.com")

    @Test
    fun `계좌 생성 시 잔액은 0이고 ACTIVE 상태다`() {
        val account = createAccount()

        assertThat(account.balance.amount).isEqualTo(0)
        assertThat(account.status).isEqualTo(AccountStatus.ACTIVE)
        assertThat(account.pullDomainEvents()).hasSize(1).first().isInstanceOf(AccountCreatedEvent::class.java)
    }

    @Test
    fun `정지된 계좌에 입금하면 예외를 던진다`() {
        val account = createAccount()
        account.suspend()

        assertThrows<DepositRequiresActiveAccountException> { account.deposit(1000) }
    }

    @Test
    fun `0 이하 금액을 입금하면 예외를 던진다`() {
        val account = createAccount()

        assertThrows<InvalidAmountException> { account.deposit(0) }
    }

    @Test
    fun `입금하면 MoneyDepositedEvent가 수집된다`() {
        val account = createAccount()
        account.pullDomainEvents()

        account.deposit(5000)

        val events = account.pullDomainEvents()
        assertThat(events).hasSize(1)
        assertThat((events.first() as MoneyDepositedEvent).amount.amount).isEqualTo(5000)
        assertThat(account.balance.amount).isEqualTo(5000)
    }

    @Test
    fun `정지된 계좌에서 출금하면 예외를 던진다`() {
        val account = createAccount()
        account.suspend()

        assertThrows<WithdrawRequiresActiveAccountException> { account.withdraw(1000) }
    }

    @Test
    fun `잔액보다 큰 금액을 출금하면 예외를 던진다`() {
        val account = createAccount()
        account.deposit(1000)

        assertThrows<InsufficientBalanceException> { account.withdraw(2000) }
    }

    @Test
    fun `출금하면 MoneyWithdrawnEvent가 수집된다`() {
        val account = createAccount()
        account.deposit(1000)
        account.pullDomainEvents()

        account.withdraw(400)

        val events = account.pullDomainEvents()
        assertThat(events).hasSize(1)
        assertThat((events.first() as MoneyWithdrawnEvent).amount.amount).isEqualTo(400)
        assertThat(account.balance.amount).isEqualTo(600)
    }

    @Test
    fun `정지하면 SUSPENDED 상태가 되고 AccountSuspendedEvent가 수집된다`() {
        val account = createAccount()
        account.pullDomainEvents()

        account.suspend()

        assertThat(account.status).isEqualTo(AccountStatus.SUSPENDED)
        assertThat(account.pullDomainEvents()).hasSize(1).first().isInstanceOf(AccountSuspendedEvent::class.java)
    }

    @Test
    fun `이미 정지된 계좌를 정지하면 예외를 던진다`() {
        val account = createAccount()
        account.suspend()

        assertThrows<SuspendRequiresActiveAccountException> { account.suspend() }
    }

    @Test
    fun `정지된 계좌를 재개하면 ACTIVE 상태가 되고 AccountReactivatedEvent가 수집된다`() {
        val account = createAccount()
        account.suspend()
        account.pullDomainEvents()

        account.reactivate()

        assertThat(account.status).isEqualTo(AccountStatus.ACTIVE)
        assertThat(account.pullDomainEvents()).hasSize(1).first().isInstanceOf(AccountReactivatedEvent::class.java)
    }

    @Test
    fun `활성 계좌를 재개하면 예외를 던진다`() {
        val account = createAccount()

        assertThrows<ReactivateRequiresSuspendedAccountException> { account.reactivate() }
    }

    @Test
    fun `잔액이 0이 아닌 계좌를 종료하면 예외를 던진다`() {
        val account = createAccount()
        account.deposit(1000)

        assertThrows<AccountBalanceNotZeroException> { account.close() }
    }

    @Test
    fun `잔액이 0인 계좌를 종료하면 CLOSED 상태가 되고 AccountClosedEvent가 수집된다`() {
        val account = createAccount()
        account.pullDomainEvents()

        account.close()

        assertThat(account.status).isEqualTo(AccountStatus.CLOSED)
        assertThat(account.pullDomainEvents()).hasSize(1).first().isInstanceOf(AccountClosedEvent::class.java)
    }

    @Test
    fun `이미 종료된 계좌를 종료하면 예외를 던진다`() {
        val account = createAccount()
        account.close()

        assertThrows<AccountAlreadyClosedException> { account.close() }
    }

    @Test
    fun `pullPendingTransactions 호출하면 대기중인 거래가 반환되고 비워진다`() {
        val account = createAccount()
        account.deposit(1000)

        val transactions = account.pullPendingTransactions()

        assertThat(transactions).hasSize(1)
        assertThat(account.pullPendingTransactions()).isEmpty()
    }
}
