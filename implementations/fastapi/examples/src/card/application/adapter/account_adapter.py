from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass(frozen=True)
class AccountView:
    """Account BC를 Card BC가 필요로 하는 최소 형태로 번역한 읽기 뷰.

    상류(Account) 모델의 `AccountStatus` enum을 그대로 노출하지 않고 `active: bool`로
    축약한다 — 상류 모델 변경이 Card 도메인으로 누수되지 않게 하는 것이 ACL의 목적이다.

    `email`은 매월 카드 사용내역 발송 배치(SendMonthlyCardStatementHandler)가 통지 수신자를
    결정하기 위해 필요하다 — Card 자신은 계좌 소유자의 이메일을 모른다.
    """

    account_id: str
    active: bool
    email: str


class AccountAdapter(ABC):
    """Account BC를 동기 조회하기 위한 Adapter 인터페이스 (Anticorruption Layer).

    카드 발급 시 연결 계좌의 존재·활성 여부를 현재 요청 안에서 즉시 확인해야 하므로
    동기 Adapter 패턴을 사용한다 (cross-domain-communication.md 참조). 구현체는
    infrastructure/account_adapter_impl.py 에 있으며, 상류의 "계좌 없음"을 Card 도메인이
    이해하는 None으로 번역한다.
    """

    @abstractmethod
    async def find_account(self, account_id: str, owner_id: str) -> AccountView | None: ...
