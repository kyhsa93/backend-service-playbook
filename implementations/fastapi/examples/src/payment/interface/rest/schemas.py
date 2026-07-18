from datetime import datetime

from pydantic import BaseModel, Field


class CreatePaymentRequest(BaseModel):
    card_id: str
    amount: int = Field(gt=0)


class CancelPaymentRequest(BaseModel):
    reason: str


class RequestRefundRequest(BaseModel):
    amount: int = Field(gt=0)
    reason: str


class PaymentResponse(BaseModel):
    payment_id: str
    card_id: str
    account_id: str
    owner_id: str
    amount: int
    status: str
    created_at: datetime


class GetPaymentsResponse(BaseModel):
    payments: list[PaymentResponse]
    count: int


class RefundResponse(BaseModel):
    refund_id: str
    payment_id: str
    amount: int
    reason: str
    status: str
    decision_note: str | None = None
    created_at: datetime


class GetRefundsResponse(BaseModel):
    refunds: list[RefundResponse]
    count: int
