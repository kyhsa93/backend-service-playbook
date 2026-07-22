package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.account.domain.TransactionType
import org.springframework.stereotype.Service

/**
 * The reaction use case for the Payment BC's `payment.completed.v1` Integration Event — this is where
 * the deduction, already decided by a synchronous Adapter at payment time, is actually carried out.
 *
 * Idempotency: unlike [WithdrawService] (a user-initiated direct withdrawal), this reaction silently
 * ignores the request if a WITHDRAWAL transaction with the same referenceId (paymentId) already exists —
 * unlike Card's state-based idempotency, moving money keeps reducing the balance each time it is
 * reapplied, so an "already processed" check is required (Level 2 Ledger, see domain-events.md).
 *
 * This Service itself has no transaction boundary and does not re-invoke the outbox relay's drain either
 * — for the same reason as
 * [com.example.accountservice.card.application.command.SuspendCardsByAccountService]: it is called from
 * within an outer transaction wrapping the outbox drain loop, so the `MoneyWithdrawnEvent` this Service
 * leaves behind is picked up by that outer component's (the outbox package's) multi-pass drain.
 */
@Service
class WithdrawByPaymentService(
    private val accountRepository: AccountRepository,
) {
    fun withdraw(command: WithdrawByPaymentCommand) {
        val alreadyProcessed =
            accountRepository.hasTransactionWithReference(command.referenceId, TransactionType.WITHDRAWAL)
        if (alreadyProcessed) return

        val (accounts, _) =
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = command.accountId))
        // Silently ignores the request if there is no account to react against (e.g. the account was
        // already deleted).
        val account = accounts.firstOrNull() ?: return

        account.withdraw(command.amount, command.referenceId)
        accountRepository.saveAccount(account)
    }
}
