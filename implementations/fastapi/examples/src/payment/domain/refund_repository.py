from abc import ABC, abstractmethod
from datetime import datetime

from .refund import Refund


class RefundQuery(ABC):
    """A read-only interface — for the Query Handler only (the same reason as the card
    domain's CardQuery).

    Refund has no owner_id (it references the original payment only via paymentId) —
    ownership verification is done by the Query Handler first looking up the original
    payment via PaymentQuery.
    """

    @abstractmethod
    async def find_refunds(
        self,
        page: int,
        take: int,
        refund_id: str | None = None,
        payment_id: str | None = None,
        status: list[str] | None = None,
    ) -> tuple[list[Refund], int]: ...


class RefundRepository(RefundQuery, ABC):
    @abstractmethod
    async def save_refund(self, refund: Refund) -> None: ...

    @abstractmethod
    async def summarize_refunds_by_owner(
        self,
        owner_id: str,
        created_at_from: datetime,
        status: list[str] | None = None,
    ) -> int:
        """A dedicated aggregate query for RefundFraudRiskScorer's feature assembly (see
        request_refund_handler.py) — the same reason AccountRepository.has_transaction_with_reference
        lives directly on a Repository rather than being expressed via find_<noun>s: counting
        an owner's refund history via find_refunds() and counting matches in the application
        wouldn't scale once history grows past a single page, and this repository-naming
        rule's blocklist (see harness/rules/repository_naming.py) deliberately only forbids a
        bare `count*`-prefixed method, not a semantically-named aggregate query like this one.
        Refund itself carries no owner_id (only payment_id), so an implementation must join
        against Payment to filter by owner.
        """
        ...
