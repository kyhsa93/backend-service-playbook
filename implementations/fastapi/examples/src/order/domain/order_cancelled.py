from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class OrderCancelled:
    order_id: str
    reason: str
    cancelled_at: datetime
