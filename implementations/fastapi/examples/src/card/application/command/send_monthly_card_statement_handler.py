from __future__ import annotations

from datetime import date, datetime

from ...domain.repository import CardRepository
from ..adapter.account_adapter import AccountAdapter
from ..adapter.payment_adapter import PaymentAdapter

PAGE_SIZE = 200


def previous_month_period(today: date) -> tuple[str, datetime, datetime]:
    """Defines "the past month" as the month right before the one `today` falls in — a
    common convention where a batch running in the early morning of the 1st of each month
    aggregates "the month that just ended." The return value is (a period label "YYYY-MM",
    since (that month's 1st at 00:00, inclusive), until (this month's 1st at 00:00,
    exclusive)).
    """
    first_of_this_month = today.replace(day=1)
    last_day_of_prev_month = first_of_this_month.toordinal() - 1
    prev_month_date = date.fromordinal(last_day_of_prev_month)
    since = datetime(prev_month_date.year, prev_month_date.month, 1)
    until = datetime(first_of_this_month.year, first_of_this_month.month, 1)
    period = f"{prev_month_date.year:04d}-{prev_month_date.month:02d}"
    return period, since, until


class SendMonthlyCardStatementHandler:
    """The Command Service for the monthly card-statement delivery batch. This is a
    system-driven use case triggered once a month by the Task Queue
    (card.statement.send) — there is no user Command dataclass, for the same reason as
    ApplyDailyInterestHandler.

    Iterates every ACTIVE card page by page, and only for a card not yet sent this month,
    looks up last month's payment count/total via PaymentAdapter, looks up the recipient
    email via AccountAdapter, then calls Card.send_statement(). This Handler doesn't
    actually send the email — the CardStatementSent Domain Event that send_statement()
    leaves is sent asynchronously through Outbox → SQS (the same flow as
    domain-events.md, introduced here for the first time in the Card BC).
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
                    # An abnormal state where the linked account can't be found — this run
                    # skips it without changing state (retried on the next Task redelivery
                    # or next month's batch).
                    continue

                summary = await self._payment_adapter.summarize_payments(card.card_id, since, until)
                card.send_statement(period, summary.payment_count, summary.total_amount, account_view.email)
                await self._repo.save_card(card)
                processed_count += 1

            if (page + 1) * PAGE_SIZE >= total:
                break
            page += 1

        return processed_count
