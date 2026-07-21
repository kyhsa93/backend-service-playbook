package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.account.domain.TransferEligibilityService
import com.example.accountservice.common.generateId
import org.springframework.stereotype.Service

/**
 * 계좌 간 송금(Transfer) — Command Service 자신은 트랜잭션 애노테이션을 갖지 않는다. 트랜잭션
 * 경계는 [AccountRepository.saveAccounts](Repository 레벨)에 있다(persistence.md,
 * WithdrawService/DepositService와 동일한 규칙).
 */
@Service
class TransferService(
    private val accountRepository: AccountRepository,
) {
    // TransferEligibilityService는 프레임워크 어노테이션이 없는 순수 Domain Service다. Spring
    // DI 컨테이너에 등록하지 않고 직접 인스턴스화해 쓴다(RefundEligibilityService와 동일한 이유).
    private val transferEligibilityService = TransferEligibilityService()

    fun transfer(command: TransferCommand): TransferResult {
        val (sourceAccounts, _) =
            accountRepository.findAccounts(
                AccountFindQuery(page = 0, take = 1, accountId = command.sourceAccountId, ownerId = command.requesterId),
            )
        val source = sourceAccounts.firstOrNull() ?: throw AccountNotFoundException(command.sourceAccountId)

        // target은 소유자 필터 없이 조회한다 — 타인 계좌로 송금하는 것이 이 기능의 목적이라,
        // 존재+활성 여부만 확인하면 된다(소유권 확인은 source에만 적용).
        val (targetAccounts, _) =
            accountRepository.findAccounts(
                AccountFindQuery(page = 0, take = 1, accountId = command.targetAccountId),
            )
        val target = targetAccounts.firstOrNull() ?: throw AccountNotFoundException(command.targetAccountId)

        val decision = transferEligibilityService.evaluate(source, target, command.amount)
        if (!decision.approved) throw decision.error!!

        // transferId는 이 송금 전용의 새 영속 Aggregate를 두지 않고, 두 Transaction 행을
        // 상관관계 짓는 referenceId로만 쓴다 — (reference_id, type) 조합이 이미 유니크하므로
        // source(WITHDRAWAL)/target(DEPOSIT) 두 행이 같은 transferId를 공유해도 충돌하지
        // 않는다. 접미사 없이 32자리 원본 그대로 쓴다(정기이체 벤치마크에서 접미사를 붙여
        // VARCHAR(36)을 넘긴 전례가 있다 — 이 기능은 그 전례를 되풀이하지 않는다).
        val transferId = generateId()
        val sourceTransaction = source.withdraw(command.amount, transferId)
        val targetTransaction = target.deposit(command.amount, transferId)

        // 두 Account 저장을 하나의 물리 트랜잭션으로 묶는다 — 그렇지 않으면 "출금은 반영됐는데
        // 입금은 유실됨" 실패 모드가 생긴다.
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
