package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.domain.AccountRepository
import org.springframework.stereotype.Service

/**
 * 계좌 소프트 삭제 유스케이스.
 *
 * `close()`(AccountStatus.CLOSED)와 소프트 삭제(deletedAt)는 서로 다른 생명주기 이벤트다.
 * CLOSED는 계좌가 조회 가능한 상태로 남아있는 것을 전제로 한다(GetAccountService가 종료 이후에도
 * 계좌 상태/이력을 계속 반환해야 하므로) — 반면 소프트 삭제는 `deletedAt IS NULL` 조건이 걸린
 * 모든 조회에서 계좌를 제외시킨다. 따라서 종료(close)를 삭제(delete)와 합치지 않고, 이미 CLOSED
 * 상태인 계좌만 삭제할 수 있도록 별도 유스케이스로 둔다 — 이 규칙은 Account.markDeleted()가
 * 도메인 레벨에서 강제한다.
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
