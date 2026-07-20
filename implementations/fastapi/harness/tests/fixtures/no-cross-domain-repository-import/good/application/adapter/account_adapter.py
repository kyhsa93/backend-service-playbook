from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass(frozen=True)
class AccountView:
    account_id: str
    active: bool


class AccountAdapter(ABC):
    @abstractmethod
    async def find_account(self, account_id: str, owner_id: str) -> AccountView | None: ...
