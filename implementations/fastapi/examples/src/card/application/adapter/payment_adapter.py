from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class CardPaymentSummary:
    """Payment BC를 Card BC가 필요로 하는 최소 형태로 번역한 읽기 뷰 — 매월 카드 사용내역
    발송 배치가 필요로 하는 건수·합계만 담는다. Payment의 개별 결제 내역(Payment 객체
    목록)을 그대로 노출하지 않는다 — 상류 모델 변경이 Card 도메인으로 누수되지 않게 하는
    것이 ACL의 목적이다(card/application/adapter/account_adapter.py의 AccountView와 동일한
    설계)."""

    payment_count: int
    total_amount: int


class PaymentAdapter(ABC):
    """Payment BC를 동기 조회하기 위한 Adapter 인터페이스 (Anticorruption Layer).

    매월 통계 배치가 한 카드의 지난 한 달 결제 건수·합계를 현재 배치 실행 안에서 즉시
    집계해야 하므로 동기 Adapter 패턴을 사용한다(cross-domain-communication.md 참조).
    구현체는 infrastructure/payment_adapter_impl.py 에 있다.
    """

    @abstractmethod
    async def summarize_payments(self, card_id: str, since: datetime, until: datetime) -> CardPaymentSummary: ...
