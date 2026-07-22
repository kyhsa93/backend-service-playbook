from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass(frozen=True)
class AccountView:
    """A read view that translates the Account BC into the minimal shape the Card BC needs.

    Rather than exposing the upstream (Account) model's `AccountStatus` enum as-is, it's
    reduced to `active: bool` — preventing an upstream model change from leaking into the
    Card domain is the ACL's purpose.

    `email` is needed for the monthly card-statement delivery batch
    (SendMonthlyCardStatementHandler) to determine the notification recipient — Card itself
    doesn't know the account owner's email.
    """

    account_id: str
    active: bool
    email: str


class AccountAdapter(ABC):
    """An Adapter interface (an Anti-Corruption Layer) for synchronously querying the
    Account BC.

    Since a card issuance must immediately confirm, within the current request, whether the
    linked account exists and is active, the synchronous Adapter pattern is used (see
    cross-domain-communication.md). The implementation lives in
    infrastructure/account_adapter_impl.py, and translates the upstream "account not found"
    into the None the Card domain understands.
    """

    @abstractmethod
    async def find_account(self, account_id: str, owner_id: str) -> AccountView | None: ...
