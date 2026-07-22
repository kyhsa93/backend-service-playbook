package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.account.domain.TransferEligibilityService
import com.example.accountservice.common.generateId
import org.springframework.stereotype.Service

/**
 * An inter-account funds transfer (Transfer) — the Command Service itself carries no transaction
 * annotation. The transaction boundary lives in [AccountRepository.saveAccounts] (the Repository level)
 * (persistence.md, the same rule as WithdrawService/DepositService).
 */
@Service
class TransferService(
    private val accountRepository: AccountRepository,
) {
    // TransferEligibilityService is a plain Domain Service with no framework annotations. It is
    // instantiated directly rather than registered in the Spring DI container (same reason as
    // RefundEligibilityService).
    private val transferEligibilityService = TransferEligibilityService()

    fun transfer(command: TransferCommand): TransferResult {
        val (sourceAccounts, _) =
            accountRepository.findAccounts(
                AccountFindQuery(page = 0, take = 1, accountId = command.sourceAccountId, ownerId = command.requesterId),
            )
        val source = sourceAccounts.firstOrNull() ?: throw AccountNotFoundException(command.sourceAccountId)

        // target is queried without an owner filter — since transferring to someone else's account is
        // exactly the point of this feature, only existence + active status need to be checked
        // (ownership verification applies only to source).
        val (targetAccounts, _) =
            accountRepository.findAccounts(
                AccountFindQuery(page = 0, take = 1, accountId = command.targetAccountId),
            )
        val target = targetAccounts.firstOrNull() ?: throw AccountNotFoundException(command.targetAccountId)

        val decision = transferEligibilityService.evaluate(source, target, command.amount)
        if (!decision.approved) throw decision.error!!

        // transferId does not introduce a new persistent Aggregate dedicated to this transfer; it is
        // used only as the referenceId correlating the two Transaction rows — since the
        // (reference_id, type) combination is already unique, the source (WITHDRAWAL)/target (DEPOSIT)
        // rows sharing the same transferId does not cause a conflict. It is used as the raw
        // 32-character value with no suffix — the reference_id column is VARCHAR(36), so adding a
        // suffix could exceed that limit.
        val transferId = generateId()
        val sourceTransaction = source.withdraw(command.amount, transferId)
        val targetTransaction = target.deposit(command.amount, transferId)

        // Bundles both Account saves into a single physical transaction — otherwise a failure mode
        // arises where "the withdrawal is applied but the deposit is lost."
        accountRepository.saveAccounts(source, target)

        return TransferResult(
            transferId = transferId,
            sourceTransaction =
                TransactionResult(
                    transactionId = sourceTransaction.transactionId,
                    accountId = sourceTransaction.accountId,
                    type = sourceTransaction.type.name,
                    amount = TransactionResult.MoneyResult(sourceTransaction.amount.amount, sourceTransaction.amount.currency),
                    createdAt = sourceTransaction.createdAt,
                ),
            targetTransaction =
                TransactionResult(
                    transactionId = targetTransaction.transactionId,
                    accountId = targetTransaction.accountId,
                    type = targetTransaction.type.name,
                    amount = TransactionResult.MoneyResult(targetTransaction.amount.amount, targetTransaction.amount.currency),
                    createdAt = targetTransaction.createdAt,
                ),
        )
    }
}
