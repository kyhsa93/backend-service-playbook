from __future__ import annotations

from datetime import date
from decimal import Decimal

from ...domain.repository import AccountRepository

PAGE_SIZE = 200


class ApplyDailyInterestHandler:
    """The Command Service for the regular interest-payment batch. Since this is a
    system-driven use case triggered once a day by the Task Queue
    (account.interest.apply), there is no Command dataclass representing a user request
    (the convention for other Handlers) — because there is no HTTP requester.

    Iterates every ACTIVE account page by page, calling Account.apply_interest(). Calls
    save_account() for every account regardless of whether interest was actually paid or
    its amount — the Handler doesn't need to distinguish whether apply_interest() updated
    `last_interest_paid_at` internally (an account already processed today doesn't even
    touch the field, so re-saving is effectively a no-op UPDATE).
    """

    def __init__(self, repo: AccountRepository) -> None:
        self._repo = repo

    async def execute(self, daily_rate: Decimal, today: date | None = None) -> int:
        today = today or date.today()
        applied_count = 0
        page = 0

        while True:
            accounts, total = await self._repo.find_accounts(page=page, take=PAGE_SIZE, status=["ACTIVE"])
            if not accounts:
                break

            for account in accounts:
                transaction = account.apply_interest(daily_rate, today)
                await self._repo.save_account(account)
                if transaction is not None:
                    applied_count += 1

            if (page + 1) * PAGE_SIZE >= total:
                break
            page += 1

        return applied_count
