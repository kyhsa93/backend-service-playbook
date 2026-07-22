from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass(frozen=True)
class AccountView:
    """A read view that translates the Account BC into the minimal shape the Payment BC
    needs.

    Unlike the Card BC's AccountView (which exposes only active), Payment must also decide
    whether payment is possible (whether the balance is sufficient), so it includes
    balance_amount/currency too — each BC independently defines its own ACL in the shape it
    needs (never simply re-exposing the upstream model).
    """

    account_id: str
    active: bool
    balance_amount: int
    currency: str


class AccountAdapter(ABC):
    """An Adapter interface (an Anti-Corruption Layer) for synchronously querying the
    Account BC.

    Since whether payment is possible (the account's active status + sufficient balance)
    must be confirmed immediately within the current request, the synchronous Adapter
    pattern is used. The actual debit isn't this synchronous lookup's job — the Account BC
    subscribes to the payment.completed.v1 Integration Event and performs it asynchronously
    (the "sync = lookup, async Integration Event = state change" principle in
    cross-domain.md).
    """

    @abstractmethod
    async def find_account(self, account_id: str, owner_id: str) -> AccountView | None: ...
