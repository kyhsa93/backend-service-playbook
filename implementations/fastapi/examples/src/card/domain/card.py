from __future__ import annotations

from datetime import datetime

from ...common.generate_id import generate_id
from .card_status import CardStatus
from .errors import (
    CancelledCardCannotBeSuspendedError,
    CardAlreadyCancelledError,
    CardAlreadySuspendedError,
)


class Card:
    def __init__(
        self,
        card_id: str,
        account_id: str,
        owner_id: str,
        brand: str,
        status: CardStatus,
        created_at: datetime,
    ) -> None:
        self.card_id = card_id
        self.account_id = account_id
        self.owner_id = owner_id
        self.brand = brand
        self.status = status
        self.created_at = created_at

    @classmethod
    def issue(cls, account_id: str, owner_id: str, brand: str) -> Card:
        # 연결 계좌의 활성 여부는 Card Aggregate가 알 수 없다 — 발급 가능 여부(계좌 상태)는
        # Application 레이어가 AccountAdapter(ACL)로 동기 조회해 판단한 뒤 이 팩토리를 호출한다.
        return cls(
            card_id=generate_id(),
            account_id=account_id,
            owner_id=owner_id,
            brand=brand,
            status=CardStatus.ACTIVE,
            created_at=datetime.utcnow(),
        )

    def suspend(self) -> None:
        if self.status == CardStatus.CANCELLED:
            raise CancelledCardCannotBeSuspendedError()
        if self.status == CardStatus.SUSPENDED:
            raise CardAlreadySuspendedError()
        self.status = CardStatus.SUSPENDED

    def cancel(self) -> None:
        if self.status == CardStatus.CANCELLED:
            raise CardAlreadyCancelledError()
        self.status = CardStatus.CANCELLED
