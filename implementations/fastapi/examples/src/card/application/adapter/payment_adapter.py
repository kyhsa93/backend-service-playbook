from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class CardPaymentSummary:
    """A read view that translates the Payment BC into the minimal shape the Card BC needs
    — carries only the count/total the monthly card-statement delivery batch needs. It
    never exposes Payment's individual payment records (a list of Payment objects) as-is —
    preventing an upstream model change from leaking into the Card domain is the ACL's
    purpose (the same design as AccountView in
    card/application/adapter/account_adapter.py)."""

    payment_count: int
    total_amount: int


class PaymentAdapter(ABC):
    """An Adapter interface (an Anti-Corruption Layer) for synchronously querying the
    Payment BC.

    Since the monthly statistics batch must immediately aggregate one card's payment
    count/total for the past month within the current batch run, the synchronous Adapter
    pattern is used (see cross-domain-communication.md). The implementation lives in
    infrastructure/payment_adapter_impl.py.
    """

    @abstractmethod
    async def summarize_payments(self, card_id: str, since: datetime, until: datetime) -> CardPaymentSummary: ...
