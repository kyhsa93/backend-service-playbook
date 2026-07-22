from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass(frozen=True)
class CardView:
    """A read view that translates the Card BC into the minimal shape the Payment BC needs.

    Rather than exposing the upstream (Card) model's `CardStatus` enum as-is, it's reduced
    to `active: bool` — Payment reuses the same ACL pattern the Card BC already uses to
    query Account this way (AccountView).
    """

    card_id: str
    account_id: str
    active: bool


class CardAdapter(ABC):
    """An Adapter interface (an Anti-Corruption Layer) for synchronously querying the
    Card BC.

    Since a payment must immediately confirm, within the current request, whether the card
    exists and is active, and what its linked account_id is, the synchronous Adapter
    pattern is used (see cross-domain.md). The implementation lives in
    infrastructure/card_adapter_impl.py, and translates the upstream "card not found" into
    the None the Payment domain understands.
    """

    @abstractmethod
    async def find_card(self, card_id: str, owner_id: str) -> CardView | None: ...
