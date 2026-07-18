package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.account.domain.TransactionType
import org.springframework.stereotype.Service

/**
 * Payment BC의 `payment.cancelled.v1`(결제취소 보상 크레딧) 및 `refund.approved.v1`(환불 승인
 * 크레딧) Integration Event 둘 다에 대한 반응 유스케이스다 — 두 이벤트는 "이미 차감된 금액을
 * 되돌린다"는 동일한 동작이고 referenceId(paymentId 또는 refundId)만 다르므로 커맨드를 하나로
 * 재사용한다.
 *
 * 멱등성은 [WithdrawByPaymentService]와 동일한 이유로 Level 2 Ledger를 쓴다. 이 Service도
 * [WithdrawByPaymentService]와 동일한 이유로 `outboxRelay.processPending()`을 호출하지 않는다.
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
