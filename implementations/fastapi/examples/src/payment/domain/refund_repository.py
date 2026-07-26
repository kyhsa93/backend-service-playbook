from abc import ABC, abstractmethod

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
