from datetime import datetime

from pydantic import BaseModel


class IssueCardRequest(BaseModel):
    account_id: str
    brand: str


class IssueCardResponse(BaseModel):
    card_id: str
    account_id: str
    owner_id: str
    brand: str
    status: str
    created_at: datetime


class GetCardResponse(BaseModel):
    card_id: str
    account_id: str
    owner_id: str
    brand: str
    status: str
    created_at: datetime
