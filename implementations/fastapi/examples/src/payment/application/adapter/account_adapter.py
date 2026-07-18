from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass(frozen=True)
class AccountView:
    """Account BC를 Payment BC가 필요로 하는 최소 형태로 번역한 읽기 뷰.

    Card BC의 AccountView(active만 노출)와 달리 Payment는 결제 가능 여부(잔액 충분 여부)도
    판단해야 하므로 balance_amount/currency까지 포함한다 — BC마다 자신이 필요로 하는
    형태로 독립적으로 ACL을 정의한다(상류 모델을 그대로 재노출하지 않는다).
    """

    account_id: str
    active: bool
    balance_amount: int
    currency: str


class AccountAdapter(ABC):
    """Account BC를 동기 조회하기 위한 Adapter 인터페이스 (Anticorruption Layer).

    결제 가능 여부(계좌 활성 여부 + 잔액 충분 여부)를 현재 요청 안에서 즉시 확인해야
    하므로 동기 Adapter 패턴을 사용한다. 실제 차감은 이 동기 조회의 몫이 아니다 —
    payment.completed.v1 Integration Event를 Account BC가 구독해 비동기로 수행한다
    (cross-domain.md의 "동기=조회, 비동기 Integration Event=상태변경" 원칙).
    """

    @abstractmethod
    async def find_account(self, account_id: str, owner_id: str) -> AccountView | None: ...
