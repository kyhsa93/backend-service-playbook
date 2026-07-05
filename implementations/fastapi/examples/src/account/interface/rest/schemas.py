from datetime import datetime

from pydantic import BaseModel, EmailStr


class MoneySchema(BaseModel):
    amount: int
    currency: str


class CreateAccountRequest(BaseModel):
    currency: str
    email: EmailStr


class DepositRequest(BaseModel):
    amount: int


class WithdrawRequest(BaseModel):
    amount: int


class CreateAccountResponse(BaseModel):
    account_id: str
    owner_id: str
    email: str
    balance: MoneySchema
    status: str
    created_at: datetime


class TransactionResponse(BaseModel):
    transaction_id: str
    account_id: str
    type: str
    amount: MoneySchema
    created_at: datetime


class GetAccountResponse(BaseModel):
    account_id: str
    owner_id: str
    email: str
    balance: MoneySchema
    status: str
    created_at: datetime
    updated_at: datetime


class TransactionSummaryResponse(BaseModel):
    transaction_id: str
    type: str
    amount: MoneySchema
    created_at: datetime


class GetTransactionsResponse(BaseModel):
    transactions: list[TransactionSummaryResponse]
    count: int
