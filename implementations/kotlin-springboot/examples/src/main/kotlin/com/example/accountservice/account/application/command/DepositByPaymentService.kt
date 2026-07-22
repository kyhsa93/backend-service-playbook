package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.account.domain.TransactionType
import org.springframework.stereotype.Service

/**
 * The reaction use case for both the Payment BC's `payment.cancelled.v1` (cancellation compensation
 * credit) and `refund.approved.v1` (refund-approval credit) Integration Events — both events perform the
 * same action, "reversing an amount already deducted," differing only in referenceId (paymentId or
 * refundId), so a single command is reused for both.
 *
 * Idempotency uses a Level 2 Ledger for the same reason as [WithdrawByPaymentService]. This Service also
 * does not call `outboxRelay.processPending()`, for the same reason as [WithdrawByPaymentService].
 */
@Service
class DepositByPaymentService(
    private val accountRepository: AccountRepository,
) {
    fun deposit(command: DepositByPaymentCommand) {
        val alreadyProcessed =
            accountRepository.hasTransactionWithReference(command.referenceId, TransactionType.DEPOSIT)
        if (alreadyProcessed) return

        val (accounts, _) =
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = command.accountId))
        val account = accounts.firstOrNull() ?: return

        account.deposit(command.amount, command.referenceId)
        accountRepository.saveAccount(account)
    }
}
