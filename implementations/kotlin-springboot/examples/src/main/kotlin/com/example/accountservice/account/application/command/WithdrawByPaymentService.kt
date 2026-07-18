package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.account.domain.TransactionType
import org.springframework.stereotype.Service

/**
 * Payment BC의 `payment.completed.v1` Integration Event에 대한 반응 유스케이스 — 결제 시점에 이미
 * 동기 Adapter로 판정된 차감을 여기서 실제로 수행한다.
 *
 * 멱등성: [WithdrawService](사용자 직접 출금)와 달리 이 반응은 같은 referenceId(paymentId)의
 * WITHDRAWAL 거래가 이미 있으면 조용히 무시한다 — Card의 상태 기반 멱등성과 달리 금액 이동은
 * 반복 적용하면 잔액이 계속 줄어들므로 "이미 처리했는지"를 확인해야 한다(Level 2 Ledger,
 * domain-events.md 참고).
 *
 * 이 Service 자신은 트랜잭션 경계를 갖지 않고, outbox 릴레이(Outbox Relay)의 드레인 재호출도 하지
 * 않는다 — [com.example.accountservice.card.application.command.SuspendCardsByAccountService]와
 * 동일한 이유로, outbox 드레인 루프를 감싼 상위 트랜잭션 안에서 호출되므로, 이 Service가 남기는
 * `MoneyWithdrawnEvent`는 그 상위 컴포넌트(outbox 패키지)의 멀티패스 드레인이 이어서 처리한다.
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
        // 반응할 대상 계좌가 없으면 조용히 무시한다(예: 계좌가 이미 삭제됨).
        val account = accounts.firstOrNull() ?: return

        account.withdraw(command.amount, command.referenceId)
        accountRepository.saveAccount(account)
    }
}
