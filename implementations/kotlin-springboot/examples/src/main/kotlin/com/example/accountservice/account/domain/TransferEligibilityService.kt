package com.example.accountservice.account.domain

/**
 * A Domain Service — a plain class with no framework annotations (not registered in the Spring DI
 * container either; the Application layer instantiates it directly as `TransferEligibilityService()`
 * when needed, for the same reason as RefundEligibilityService).
 *
 * The judgment "are the withdrawal account and the deposit account different, are both active, do their
 * currencies match, and does the withdrawal account have a sufficient balance" cannot be made from
 * either [Account] instance alone — both Aggregate instances must be loaded and compared side by side.
 */
class TransferEligibilityService {
    fun evaluate(
        source: Account,
        target: Account,
        amount: Long,
    ): TransferDecision {
        if (source.accountId == target.accountId) {
            return TransferDecision(approved = false, error = TransferSameAccountException())
        }
        if (source.status != AccountStatus.ACTIVE) {
            return TransferDecision(approved = false, error = WithdrawRequiresActiveAccountException())
        }
        if (target.status != AccountStatus.ACTIVE) {
            return TransferDecision(approved = false, error = DepositRequiresActiveAccountException())
        }
        if (source.balance.currency != target.balance.currency) {
            return TransferDecision(approved = false, error = CurrencyMismatchException())
        }
        if (source.balance.amount < amount) {
            return TransferDecision(approved = false, error = InsufficientBalanceException())
        }
        return TransferDecision(approved = true)
    }
}

/**
 * The judgment result of [TransferEligibilityService.evaluate]. Unlike [RefundDecision]'s
 * `reason: String?` shape, this holds an actual [AccountException] instance on rejection — unlike
 * Refund, Transfer has no persistent Aggregate of its own (there is nothing to store a rejection
 * against), so a rejection must be thrown as an exception immediately, and that exception must be
 * exactly the same one the user would get by calling withdraw/deposit directly. This is an intentional
 * difference, so do not revert it to the RefundDecision shape.
 */
data class TransferDecision(
    val approved: Boolean,
    val error: AccountException? = null,
)
