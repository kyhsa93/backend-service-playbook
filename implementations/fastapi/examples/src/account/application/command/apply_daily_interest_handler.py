from __future__ import annotations

from datetime import date
from decimal import Decimal

from ...domain.repository import AccountRepository

PAGE_SIZE = 200


class ApplyDailyInterestHandler:
    """정기 이자 지급 배치의 Command Service. Task Queue(account.interest.apply)가
    하루 한 번 트리거하는 시스템 주도 유스케이스이므로, 사용자 요청을 표현하는
    Command dataclass(다른 Handler들의 관례)는 없다 — HTTP 요청자가 없기 때문이다.

    ACTIVE 상태의 모든 계좌를 페이지 단위로 순회하며 Account.apply_interest()를 호출한다.
    이자 지급 여부·금액과 무관하게 매 계좌마다 save_account()를 호출한다 — apply_interest()가
    내부에서 `last_interest_paid_at`을 갱신했는지 여부를 Handler가 굳이 구분할 필요가
    없다(이미 오늘 처리된 계좌는 필드조차 안 건드리므로 재저장이 사실상 no-op UPDATE다).
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
