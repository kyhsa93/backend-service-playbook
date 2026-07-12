from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime


@dataclass
class GetCardResult:
    card_id: str
    account_id: str
    owner_id: str
    brand: str
    status: str
    created_at: datetime
