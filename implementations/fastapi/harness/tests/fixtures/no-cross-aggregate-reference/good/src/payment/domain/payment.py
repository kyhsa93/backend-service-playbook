from datetime import datetime


class Payment:
    def __init__(self, payment_id: str, amount: int, created_at: datetime) -> None:
        self.payment_id = payment_id
        self.amount = amount
        self.created_at = created_at
