from abc import ABC, abstractmethod
from datetime import datetime

from .payment import Payment


class PaymentQuery(ABC):
    """A read-only interface — for the Query Handler only. Never exposes a write method
    such as `save()` (see cqrs-pattern.md). Shares its contract with `PaymentRepository`
    (the write model) but is a separate type — a Query Handler must always depend only on
    this type (the same as the account domain's AccountQuery).

    The lookup method is unified into a single `find_payments(...)` — expressed with a
    single method + optional filter keyword arguments, the same as the account domain's
    AccountQuery. A single-item lookup is expressed with `take=1`.

    `since`/`until` were added so the monthly card-statement delivery batch
    (card.application.adapter.payment_adapter's PaymentAdapter) can aggregate payments over
    the "past month" range — expressed by adding optional filters onto the existing
    find_payments() rather than creating a new method (conversely, adding a new `count_*`
    method would be the pattern the repository-naming rule forbids).
    """

    @abstractmethod
    async def find_payments(
        self,
        page: int,
        take: int,
        payment_id: str | None = None,
        owner_id: str | None = None,
        card_id: str | None = None,
        account_id: str | None = None,
        status: list[str] | None = None,
        since: datetime | None = None,
        until: datetime | None = None,
    ) -> tuple[list[Payment], int]: ...


class PaymentRepository(PaymentQuery, ABC):
    @abstractmethod
    async def save_payment(self, payment: Payment) -> None: ...
