from datetime import datetime


class Refund:
    def __init__(self, refund_id: str, payment_id: str, amount: int, created_at: datetime) -> None:
        self.refund_id = refund_id
        self.payment_id = payment_id
        self.amount = amount
        self.created_at = created_at
