from __future__ import annotations

from datetime import datetime
from typing import Union

from ...common.generate_id import generate_id
from .card_status import CardStatus
from .errors import (
    CancelledCardCannotBeSuspendedError,
    CardAlreadyCancelledError,
    CardAlreadySuspendedError,
)
from .events import CardStatementSent

CardDomainEvent = Union[CardStatementSent]


class Card:
    def __init__(
        self,
        card_id: str,
        account_id: str,
        owner_id: str,
        brand: str,
        status: CardStatus,
        created_at: datetime,
        last_statement_sent_month: str | None = None,
    ) -> None:
        self.card_id = card_id
        self.account_id = account_id
        self.owner_id = owner_id
        self.brand = brand
        self.status = status
        self.created_at = created_at
        # 매월 카드 사용내역 발송 배치의 Level 1 멱등성 마커("YYYY-MM") —
        # Account.last_interest_paid_at과 동일한 설계(domain-events.md "이벤트 핸들러 멱등성").
        self.last_statement_sent_month = last_statement_sent_month
        self._events: list[CardDomainEvent] = []

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

    def send_statement(self, period: str, payment_count: int, total_amount: int, email: str) -> None:
        """매월 카드 사용내역 발송 배치(Task Queue → card.statement.send)가 시스템 주도로
        호출하는 Aggregate 메서드다. 결제 건수·합계 계산은 이미 Application 레이어가
        PaymentAdapter(ACL)로 동기 조회해 끝낸 뒤 호출된다 — Card 자신은 Payment BC를 모른다.

        멱등성은 `last_statement_sent_month`(이번 달 이미 발송했는가) 하나로 보장한다
        (Level 1) — 이미 이번 달 처리됐다면 완전한 no-op이다.
        """
        if self.last_statement_sent_month == period:
            return
        self.last_statement_sent_month = period
        self._events.append(
            CardStatementSent(
                card_id=self.card_id,
                account_id=self.account_id,
                owner_id=self.owner_id,
                email=email,
                period=period,
                payment_count=payment_count,
                total_amount=total_amount,
                sent_at=datetime.utcnow(),
            )
        )

    def pull_events(self) -> list[CardDomainEvent]:
        events, self._events = self._events, []
        return events
