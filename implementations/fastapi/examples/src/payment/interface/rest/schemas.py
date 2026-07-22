from datetime import datetime

from pydantic import BaseModel, Field


class CreatePaymentRequest(BaseModel):
    card_id: str = Field(description="The `card_id` of the active card to charge.")
    amount: int = Field(gt=0, description="The amount to charge. Must be a positive integer.")


class CancelPaymentRequest(BaseModel):
    reason: str = Field(description="A human-readable reason the payment is being cancelled.")


class RequestRefundRequest(BaseModel):
    amount: int = Field(gt=0, description="The amount to refund. Must be a positive integer.")
    reason: str = Field(description="A human-readable reason for the refund request.")


class PaymentResponse(BaseModel):
    payment_id: str = Field(description="The unique identifier of the payment.")
    card_id: str = Field(description="The `card_id` that was charged.")
    account_id: str = Field(description="The `account_id` linked to the charged card.")
    owner_id: str = Field(description="The `user_id` of the authenticated requester who owns this payment.")
    amount: int = Field(description="The charged amount.")
    status: str = Field(
        description="The payment's lifecycle status (`PENDING`, `COMPLETED`, `FAILED`, or `CANCELLED`)."
    )
    created_at: datetime = Field(description="When the payment was created, in UTC.")


class GetPaymentsResponse(BaseModel):
    payments: list[PaymentResponse] = Field(
        description="The requester's payments, newest first, for the requested page."
    )
    count: int = Field(description="The total number of payments across all pages, not just this page's size.")


class RefundResponse(BaseModel):
    refund_id: str = Field(description="The unique identifier of the refund request.")
    payment_id: str = Field(description="The `payment_id` this refund was requested against.")
    amount: int = Field(description="The requested refund amount.")
    reason: str = Field(description="The requester-supplied reason for the refund.")
    status: str = Field(description="The refund's status (`REQUESTED`, `APPROVED`, `REJECTED`, or `COMPLETED`).")
    decision_note: str | None = Field(
        default=None, description="An explanation of the approval/rejection decision, if one was recorded."
    )
    created_at: datetime = Field(description="When the refund was requested, in UTC.")


class GetRefundsResponse(BaseModel):
    refunds: list[RefundResponse] = Field(
        description="The refunds requested against the payment, newest first, for the requested page."
    )
    count: int = Field(description="The total number of refunds across all pages, not just this page's size.")
