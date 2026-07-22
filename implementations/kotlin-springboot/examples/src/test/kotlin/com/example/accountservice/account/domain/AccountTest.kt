package com.example.accountservice.account.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AccountTest {
    private fun createAccount(currency: String = "KRW"): Account =
        Account.create(ownerId = "owner-1", currency = currency, email = "owner-1@example.com")

    @Test
    fun `creating an account starts with a 0 balance and the ACTIVE status`() {
        val account = createAccount()

        assertThat(account.balance.amount).isEqualTo(0)
        assertThat(account.status).isEqualTo(AccountStatus.ACTIVE)
        assertThat(account.pullDomainEvents()).hasSize(1).first().isInstanceOf(AccountCreatedEvent::class.java)
    }

    @Test
    fun `the account ID is a 32-character hex string without hyphens`() {
        val account = createAccount()

        assertThat(account.accountId).matches("^[0-9a-f]{32}$")
    }

    @Test
    fun `depositing to a suspended account throws an exception`() {
        val account = createAccount()
        account.suspend()

        assertThrows<DepositRequiresActiveAccountException> { account.deposit(1000) }
    }

    @Test
    fun `depositing an amount of 0 or less throws an exception`() {
        val account = createAccount()

        assertThrows<InvalidAmountException> { account.deposit(0) }
    }

    @Test
    fun `depositing collects a MoneyDepositedEvent`() {
        val account = createAccount()
        account.pullDomainEvents()

        account.deposit(5000)

        val events = account.pullDomainEvents()
        assertThat(events).hasSize(1)
        assertThat((events.first() as MoneyDepositedEvent).amount.amount).isEqualTo(5000)
        assertThat(account.balance.amount).isEqualTo(5000)
        assertThat(account.pullPendingTransactions().first().transactionId).matches("^[0-9a-f]{32}$")
    }

    @Test
    fun `withdrawing from a suspended account throws an exception`() {
        val account = createAccount()
        account.suspend()

        assertThrows<WithdrawRequiresActiveAccountException> { account.withdraw(1000) }
    }

    @Test
    fun `withdrawing more than the balance throws an exception`() {
        val account = createAccount()
        account.deposit(1000)

        assertThrows<InsufficientBalanceException> { account.withdraw(2000) }
    }

    @Test
    fun `withdrawing collects a MoneyWithdrawnEvent`() {
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
    fun `suspending transitions to SUSPENDED and collects an AccountSuspendedEvent`() {
        val account = createAccount()
        account.pullDomainEvents()

        account.suspend()

        assertThat(account.status).isEqualTo(AccountStatus.SUSPENDED)
        assertThat(account.pullDomainEvents()).hasSize(1).first().isInstanceOf(AccountSuspendedEvent::class.java)
    }

    @Test
    fun `suspending an already-suspended account throws an exception`() {
        val account = createAccount()
        account.suspend()

        assertThrows<SuspendRequiresActiveAccountException> { account.suspend() }
    }

    @Test
    fun `reactivating a suspended account transitions to ACTIVE and collects an AccountReactivatedEvent`() {
        val account = createAccount()
        account.suspend()
        account.pullDomainEvents()

        account.reactivate()

        assertThat(account.status).isEqualTo(AccountStatus.ACTIVE)
        assertThat(account.pullDomainEvents()).hasSize(1).first().isInstanceOf(AccountReactivatedEvent::class.java)
    }

    @Test
    fun `reactivating an active account throws an exception`() {
        val account = createAccount()

        assertThrows<ReactivateRequiresSuspendedAccountException> { account.reactivate() }
    }

    @Test
    fun `closing an account with a non-zero balance throws an exception`() {
        val account = createAccount()
        account.deposit(1000)

        assertThrows<AccountBalanceNotZeroException> { account.close() }
    }

    @Test
    fun `closing an account with a 0 balance transitions to CLOSED and collects an AccountClosedEvent`() {
        val account = createAccount()
        account.pullDomainEvents()

        account.close()

        assertThat(account.status).isEqualTo(AccountStatus.CLOSED)
        assertThat(account.pullDomainEvents()).hasSize(1).first().isInstanceOf(AccountClosedEvent::class.java)
    }

    @Test
    fun `closing an already-closed account throws an exception`() {
        val account = createAccount()
        account.close()

        assertThrows<AccountAlreadyClosedException> { account.close() }
    }

    @Test
    fun `deleting an account that is not closed throws an exception`() {
        val account = createAccount()

        assertThrows<DeleteRequiresClosedAccountException> { account.markDeleted() }
    }

    @Test
    fun `deleting a closed account sets deletedAt`() {
        val account = createAccount()
        account.close()

        account.markDeleted()

        assertThat(account.deletedAt).isNotNull()
    }

    @Test
    fun `calling pullPendingTransactions returns the pending transactions and clears them`() {
        val account = createAccount()
        account.deposit(1000)

        val transactions = account.pullPendingTransactions()

        assertThat(transactions).hasSize(1)
        assertThat(account.pullPendingTransactions()).isEmpty()
    }
}
