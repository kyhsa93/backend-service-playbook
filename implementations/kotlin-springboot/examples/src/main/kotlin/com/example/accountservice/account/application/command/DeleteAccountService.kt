package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.domain.AccountRepository
import org.springframework.stereotype.Service

/**
 * The account soft-delete use case.
 *
 * `close()` (AccountStatus.CLOSED) and soft delete (deletedAt) are distinct lifecycle events. CLOSED
 * assumes the account remains in a queryable state (since GetAccountService must keep returning the
 * account's status/history even after closing) — soft delete, on the other hand, excludes the account
 * from every query that carries a `deletedAt IS NULL` condition. So closing (close) is not merged with
 * deleting (delete); deletion is kept as a separate use case that only allows deleting an account already
 * in the CLOSED state — this rule is enforced at the domain level by Account.markDeleted().
 */
@Service
class DeleteAccountService(
    private val accountRepository: AccountRepository,
) {
    fun delete(command: DeleteAccountCommand) {
        val (accounts, _) =
            accountRepository.findAccounts(
                AccountFindQuery(page = 0, take = 1, accountId = command.accountId, ownerId = command.requesterId),
            )
        accounts.firstOrNull() ?: throw AccountNotFoundException(command.accountId)
        accountRepository.deleteAccount(command.accountId)
    }
}
