from datetime import datetime

from pydantic import BaseModel


class TransactionSummaryResponse(BaseModel):
    transaction_id: str
    created_at: datetime


class GetTransactionsResponse(BaseModel):
    items: list[TransactionSummaryResponse]
    count: int
