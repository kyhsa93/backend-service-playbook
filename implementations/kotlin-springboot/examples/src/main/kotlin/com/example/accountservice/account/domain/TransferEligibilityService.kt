package com.example.accountservice.account.domain

/**
 * Domain Service — 프레임워크 어노테이션이 없는 순수 클래스(Spring DI 컨테이너에도 등록하지 않는다.
 * Application 레이어가 필요할 때 직접 `TransferEligibilityService()`로 인스턴스화해 쓴다,
 * RefundEligibilityService와 동일한 이유).
 *
 * "출금 계좌와 입금 계좌가 서로 다르고, 둘 다 활성 상태이며, 통화가 같고, 출금 계좌 잔액이
 * 충분한가"라는 판단은 어느 한쪽 [Account] 인스턴스만으로는 내릴 수 없다 — 두 Aggregate
 * 인스턴스를 모두 로드해 같은 자리에서 비교해야 한다.
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
 * [TransferEligibilityService.evaluate]의 판단 결과. 기존 [RefundDecision]의
 * `reason: String?` 형태를 그대로 따르지 않고 거부 시 실제 [AccountException] 인스턴스를 들고
 * 있다 — Transfer는 Refund와 달리 자신만의 영속 Aggregate가 없어(거부를 저장할 대상이 없음)
 * 거부가 곧바로 예외로 던져져야 하고, 그 예외는 사용자가 직접 withdraw/deposit을 호출했을
 * 때와 완전히 동일해야 한다. 의도적인 차이이니 RefundDecision 모양으로 되돌리지 않는다.
 */
data class TransferDecision(
    val approved: Boolean,
    val error: AccountException? = null,
)
