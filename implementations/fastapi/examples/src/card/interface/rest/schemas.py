from datetime import datetime

from pydantic import BaseModel, Field


class IssueCardRequest(BaseModel):
    account_id: str = Field(description="The `account_id` of the active account this card is linked to.")
    brand: str = Field(description="The card network/brand (e.g. `VISA`, `MASTERCARD`).")


class IssueCardResponse(BaseModel):
    card_id: str = Field(description="The unique identifier of the newly issued card.")
    account_id: str = Field(description="The `account_id` this card is linked to.")
    owner_id: str = Field(description="The `user_id` of the authenticated requester who owns this card.")
    brand: str = Field(description="The card network/brand (e.g. `VISA`, `MASTERCARD`).")
    status: str = Field(description="The card's lifecycle status (`ACTIVE`, `SUSPENDED`, or `CANCELLED`).")
    created_at: datetime = Field(description="When the card was issued, in UTC.")


class GetCardResponse(BaseModel):
    card_id: str = Field(description="The unique identifier of the card.")
    account_id: str = Field(description="The `account_id` this card is linked to.")
    owner_id: str = Field(description="The `user_id` of the authenticated requester who owns this card.")
    brand: str = Field(description="The card network/brand (e.g. `VISA`, `MASTERCARD`).")
    status: str = Field(description="The card's lifecycle status (`ACTIVE`, `SUSPENDED`, or `CANCELLED`).")
    created_at: datetime = Field(description="When the card was issued, in UTC.")
