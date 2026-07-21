from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class CardStatementSent:
    """매월 카드 사용내역 발송 배치(Task Queue → card.statement.send)가
    `Card.send_statement()`를 통해 발행하는 Domain Event. Card BC가 발행하는 첫 Domain
    Event다 — 실제 SES 발송은 이 이벤트를 Outbox → SQS 경유로 비동기 수신하는
    `CardStatementSentEventHandler`가 담당한다(domain-events.md와 동일한 흐름). 이렇게
    하면 통계 배치의 트랜잭션(여러 카드를 한 세션에서 순회)과 실제 이메일 발송이 완전히
    분리되어, 배치 도중 다른 카드 처리가 실패해도 이미 커밋된 카드의 이메일이 중복
    발송되지 않는다."""

    card_id: str
    account_id: str
    owner_id: str
    email: str
    period: str  # "YYYY-MM" — 집계 대상 월
    payment_count: int
    total_amount: int
    sent_at: datetime
