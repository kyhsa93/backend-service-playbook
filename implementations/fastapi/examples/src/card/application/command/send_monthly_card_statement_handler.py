from __future__ import annotations

from datetime import date, datetime

from ...domain.repository import CardRepository
from ..adapter.account_adapter import AccountAdapter
from ..adapter.payment_adapter import PaymentAdapter

PAGE_SIZE = 200


def previous_month_period(today: date) -> tuple[str, datetime, datetime]:
    """`today`가 속한 달의 바로 전 달을 "지난 한 달"로 정의한다 — 매월 1일 새벽에 도는
    배치가 "직전에 끝난 달"을 집계 대상으로 삼는 흔한 관례다. 반환값은
    (기간 라벨 "YYYY-MM", since(그 달 1일 00:00, 포함), until(이번 달 1일 00:00, 미포함))이다.
    """
    first_of_this_month = today.replace(day=1)
    last_day_of_prev_month = first_of_this_month.toordinal() - 1
    prev_month_date = date.fromordinal(last_day_of_prev_month)
    since = datetime(prev_month_date.year, prev_month_date.month, 1)
    until = datetime(first_of_this_month.year, first_of_this_month.month, 1)
    period = f"{prev_month_date.year:04d}-{prev_month_date.month:02d}"
    return period, since, until


class SendMonthlyCardStatementHandler:
    """매월 카드 사용내역 발송 배치의 Command Service. Task Queue(card.statement.send)가
    한 달에 한 번 트리거하는 시스템 주도 유스케이스다 — ApplyDailyInterestHandler와 동일한
    이유로 사용자 Command dataclass가 없다.

    ACTIVE 상태의 모든 카드를 페이지 단위로 순회하며, 이번 달 아직 발송하지 않은 카드에
    대해서만 PaymentAdapter로 지난 한 달 결제 건수·합계를 조회하고, AccountAdapter로
    수신자 이메일을 조회한 뒤 Card.send_statement()를 호출한다. 실제 이메일 발송은 이
    Handler가 하지 않는다 — send_statement()가 남긴 CardStatementSent Domain Event가
    Outbox → SQS를 거쳐 비동기로 발송된다(domain-events.md와 동일한 흐름, Card BC에
    처음 도입).
    """

    def __init__(self, repo: CardRepository, account_adapter: AccountAdapter, payment_adapter: PaymentAdapter) -> None:
        self._repo = repo
        self._account_adapter = account_adapter
        self._payment_adapter = payment_adapter

    async def execute(self, today: date | None = None) -> int:
        today = today or date.today()
        period, since, until = previous_month_period(today)
        processed_count = 0
        page = 0

        while True:
            cards, total = await self._repo.find_cards(page=page, take=PAGE_SIZE, status=["ACTIVE"])
            if not cards:
                break

            for card in cards:
                if card.last_statement_sent_month == period:
                    continue

                account_view = await self._account_adapter.find_account(card.account_id, card.owner_id)
                if account_view is None:
                    # 연결 계좌를 찾을 수 없는 이상 상태 — 이번 실행에서는 건너뛰고 상태를
                    # 바꾸지 않는다(다음 Task 재전달 또는 다음 달 배치에서 다시 시도된다).
                    continue

                summary = await self._payment_adapter.summarize_payments(card.card_id, since, until)
                card.send_statement(period, summary.payment_count, summary.total_amount, account_view.email)
                await self._repo.save_card(card)
                processed_count += 1

            if (page + 1) * PAGE_SIZE >= total:
                break
            page += 1

        return processed_count
