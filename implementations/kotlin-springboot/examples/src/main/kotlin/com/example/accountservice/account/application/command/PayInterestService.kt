package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.account.domain.AccountStatus
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * The recurring interest-payment use case — called by
 * [com.example.accountservice.account.interfaces.task.PayInterestTaskController] (the Task Queue's
 * `account.pay-interest` handler). Since this is a batch job triggered by the system (Scheduler) rather
 * than a Command the user requests directly, there is no `*Command` DTO invoked straight from REST the
 * way DepositService has — it takes only [payDate] (in line with root scheduling.md's "a Task Controller
 * delegates to the Command with no logic," the date computation itself is not done here (Application);
 * `InterestPaymentScheduler` already finishes it at enqueue time and passes it in the payload).
 *
 * The Repository query stage already filters by `status = ACTIVE` and `excludeInterestPaidDate = payDate`
 * (excluding accounts that already received interest today), so the loop only needs to skip the `null`
 * that [com.example.accountservice.account.domain.Account.payInterest] returns (a defensive re-check).
 */
@Service
class PayInterestService(
    private val accountRepository: AccountRepository,
) {
    fun payInterest(payDate: LocalDate) {
        val (accounts, _) =
            accountRepository.findAccounts(
                AccountFindQuery(
                    page = 0,
                    take = MAX_ACCOUNTS_PER_RUN,
                    status = listOf(AccountStatus.ACTIVE.name),
                    excludeInterestPaidDate = payDate,
                ),
            )
        for (account in accounts) {
            account.payInterest(payDate) ?: continue
            accountRepository.saveAccount(account)
        }
    }

    companion object {
        // The upper bound on the number of accounts processed per Task run — same intent as
        // CancelCardsByAccountService (take = 1000): since this is an internal batch loop rather than
        // REST pagination, it is given a generously large value. At a scale beyond this value (a
        // real-world scenario beyond the demo scope this repository covers), a single day's Task may not
        // be able to finish everything — a genuinely large-scale batch would need to be redesigned to
        // use cursor-based pagination across multiple Tasks/multiple ticks instead of this upper-bound
        // take approach (a known limitation, left out of this repository's scope).
        private const val MAX_ACCOUNTS_PER_RUN = 10_000
    }
}
