from __future__ import annotations

from dataclasses import dataclass

from .account import Account
from .account_status import AccountStatus
from .errors import (
    AccountError,
    CurrencyMismatchError,
    DepositRequiresActiveAccountError,
    InsufficientBalanceError,
    TransferSameAccountError,
    WithdrawRequiresActiveAccountError,
)


@dataclass(frozen=True)
class TransferDecision:
    approved: bool
    # 기존 RefundDecision의 `reason: str | None` 형태를 그대로 따르지 않고 거부 시 실제
    # AccountError 인스턴스를 들고 있다 — Transfer는 Refund와 달리 자신만의 영속 Aggregate가
    # 없어(거부를 저장할 대상이 없음) 거부가 곧바로 예외로 던져져야 하고, 그 예외는 사용자가
    # 직접 withdraw/deposit을 호출했을 때와 완전히 동일해야 한다. 의도적인 차이이니
    # RefundDecision 모양으로 되돌리지 않는다.
    error: AccountError | None = None


class TransferEligibilityService:
    """Domain Service — 프레임워크 의존이 없는 순수 클래스다. FastAPI의 Depends 등 어떤 DI
    컨테이너에도 등록하지 않는다 — Application 레이어가 필요할 때 직접 생성해 쓴다
    (RefundEligibilityService와 동일한 이유).

    "출금 계좌와 입금 계좌가 서로 다르고, 둘 다 활성 상태이며, 통화가 같고, 출금 계좌 잔액이
    충분한가"라는 판단은 어느 한쪽 Account 인스턴스만으로는 내릴 수 없다 — 두 Aggregate
    인스턴스를 모두 로드해 같은 자리에서 비교해야 한다.
    """

    def evaluate(self, source: Account, target: Account, amount: int) -> TransferDecision:
        if source.account_id == target.account_id:
            return TransferDecision(approved=False, error=TransferSameAccountError())
        if source.status != AccountStatus.ACTIVE:
            return TransferDecision(approved=False, error=WithdrawRequiresActiveAccountError())
        if target.status != AccountStatus.ACTIVE:
            return TransferDecision(approved=False, error=DepositRequiresActiveAccountError())
        if source.balance.currency != target.balance.currency:
            return TransferDecision(approved=False, error=CurrencyMismatchError())
        if source.balance.amount < amount:
            return TransferDecision(approved=False, error=InsufficientBalanceError())
        return TransferDecision(approved=True)
