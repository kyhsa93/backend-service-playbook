from __future__ import annotations

from datetime import datetime
from typing import Union

from ...common.generate_id import generate_id
from .errors import (
    PaymentCancelRequiresCompletedPaymentError,
    PaymentCompleteRequiresPendingPaymentError,
    PaymentFailRequiresPendingPaymentError,
)
from .events import PaymentCancelled, PaymentCompleted
from .payment_status import PaymentStatus

PaymentDomainEvent = Union[PaymentCompleted, PaymentCancelled]


class Payment:
    """Payment Aggregate. card_id로 어느 카드를 썼는지, account_id로 어느 계좌가 차감
    대상인지 참조만 하고(BC 경계를 넘는 FK 없음) 카드·계좌의 실제 상태·잔액 판단은
    Application 레이어가 CardAdapter/AccountAdapter(ACL)로 동기 조회해 이 Aggregate를
    생성하기 전에 끝낸다 — Payment 자신은 "카드가 활성인지", "잔액이 충분한지"를
    알지 못한다.
    """

    def __init__(
        self,
        payment_id: str,
        card_id: str,
        account_id: str,
        owner_id: str,
        amount: int,
        status: PaymentStatus,
        created_at: datetime,
    ) -> None:
        self.payment_id = payment_id
        self.card_id = card_id
        self.account_id = account_id
        self.owner_id = owner_id
        self.amount = amount
        self.status = status
        self.created_at = created_at
        self._events: list[PaymentDomainEvent] = []

    @classmethod
    def create(cls, card_id: str, account_id: str, owner_id: str, amount: int) -> Payment:
        # 카드 활성 여부·계좌 잔액 충분 여부는 이미 Application 레이어의 동기 Adapter 호출로
        # 판정이 끝난 뒤 호출되는 순수 생성 팩토리다 — PENDING으로만 만들고 이벤트는 없다.
        return cls(
            payment_id=generate_id(),
            card_id=card_id,
            account_id=account_id,
            owner_id=owner_id,
            amount=amount,
            status=PaymentStatus.PENDING,
            created_at=datetime.utcnow(),
        )

    def complete(self) -> None:
        if self.status != PaymentStatus.PENDING:
            raise PaymentCompleteRequiresPendingPaymentError()
        self.status = PaymentStatus.COMPLETED
        self._events.append(
            PaymentCompleted(
                payment_id=self.payment_id,
                card_id=self.card_id,
                account_id=self.account_id,
                owner_id=self.owner_id,
                amount=self.amount,
                completed_at=datetime.utcnow(),
            )
        )

    def fail(self, reason: str) -> None:
        # 현재 CreatePaymentHandler는 통과 여부를 생성 이전에 동기 Adapter로 판정하므로
        # Payment Aggregate가 PENDING으로 만들어진 뒤 실패하는 경로는 없다. 다만 향후
        # 결제 게이트웨이 콜백처럼 비동기로 실패가 도착하는 시나리오를 대비해 상태 전이
        # 자체는 Aggregate가 갖고 있는다(Domain 단위 테스트로 검증). 아직 이 경로로
        # 진입하는 Command가 없어 reason을 실을 Domain Event 소비자도 없으므로
        # cancel()과 달리 이벤트를 발행하지 않는다.
        if self.status != PaymentStatus.PENDING:
            raise PaymentFailRequiresPendingPaymentError()
        self.status = PaymentStatus.FAILED

    def cancel(self, reason: str) -> None:
        # 결제취소는 이미 확정된(COMPLETED) 결제를 되돌리는 것이므로 COMPLETED에서만 가능하다.
        if self.status != PaymentStatus.COMPLETED:
            raise PaymentCancelRequiresCompletedPaymentError()
        self.status = PaymentStatus.CANCELLED
        self._events.append(
            PaymentCancelled(
                payment_id=self.payment_id,
                account_id=self.account_id,
                owner_id=self.owner_id,
                amount=self.amount,
                reason=reason,
                cancelled_at=datetime.utcnow(),
            )
        )

    def pull_events(self) -> list[PaymentDomainEvent]:
        events, self._events = self._events, []
        return events
