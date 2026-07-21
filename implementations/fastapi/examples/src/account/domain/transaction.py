from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Literal

from ...common.generate_id import generate_id
from .money import Money

TransactionType = Literal["DEPOSIT", "WITHDRAWAL", "INTEREST"]


@dataclass(frozen=True)
class Transaction:
    transaction_id: str
    account_id: str
    type: TransactionType
    amount: Money
    created_at: datetime
    # 외부 BC(Payment)의 Integration Event 반응으로 발생한 거래를 다른 BC의 Aggregate
    # ID(payment_id/refund_id)로 상관관계 지을 수 있게 하는 선택 필드다. 사용자가 직접
    # 요청한 입금/출금에는 없다(None) — Payment 반응 커맨드에서만 채워지며, at-least-once
    # 재수신 시 이 값(+type)으로 중복 처리를 막는 Level 2 Ledger 키로 쓰인다
    # (domain-events.md의 "이벤트 핸들러 멱등성" 참고).
    reference_id: str | None = None

    @classmethod
    def create(
        cls, account_id: str, type: TransactionType, amount: Money, reference_id: str | None = None
    ) -> Transaction:
        return cls(
            transaction_id=generate_id(),
            account_id=account_id,
            type=type,
            amount=amount,
            created_at=datetime.utcnow(),
            reference_id=reference_id,
        )
