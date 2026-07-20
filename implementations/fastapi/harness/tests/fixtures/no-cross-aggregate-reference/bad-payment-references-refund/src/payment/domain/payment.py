from datetime import datetime

from .refund import Refund


class Payment:
    def __init__(self, payment_id: str, amount: int, created_at: datetime, refund: Refund | None = None) -> None:
        self.payment_id = payment_id
        self.amount = amount
        self.created_at = created_at
        self.refund = refund
