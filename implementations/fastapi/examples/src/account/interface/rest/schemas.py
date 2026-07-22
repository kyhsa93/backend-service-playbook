from datetime import datetime

from pydantic import BaseModel, EmailStr, Field


class MoneySchema(BaseModel):
    amount: int = Field(description="The amount, in the smallest unit of `currency` (e.g. cents for USD).")
    currency: str = Field(description="The ISO 4217 currency code (e.g. `USD`).")


class CreateAccountRequest(BaseModel):
    currency: str = Field(description="The ISO 4217 currency code the account is opened in (e.g. `USD`).")
    email: EmailStr = Field(description="The account owner's email address, notified on account events.")


class DepositRequest(BaseModel):
    amount: int = Field(description="The amount to credit. Must be a positive integer.")


class WithdrawRequest(BaseModel):
    amount: int = Field(description="The amount to debit. Must be a positive integer.")


class TransferRequest(BaseModel):
    target_account_id: str = Field(description="The `account_id` of the account receiving the transfer.")
    amount: int = Field(description="The amount to transfer. Must be a positive integer.")


class CreateAccountResponse(BaseModel):
    account_id: str = Field(description="The unique identifier of the newly created account.")
    owner_id: str = Field(description="The `user_id` of the authenticated requester who owns this account.")
    email: str = Field(description="The account owner's email address.")
    balance: MoneySchema = Field(description="The account balance, 0 immediately after creation.")
    status: str = Field(description="The account's lifecycle status (`ACTIVE`, `SUSPENDED`, or `CLOSED`).")
    created_at: datetime = Field(description="When the account was created, in UTC.")


class TransactionResponse(BaseModel):
    transaction_id: str = Field(description="The unique identifier of this transaction.")
    account_id: str = Field(description="The account this transaction was recorded against.")
    type: str = Field(description="The transaction type (`DEPOSIT`, `WITHDRAWAL`, or `INTEREST`).")
    amount: MoneySchema = Field(description="The transaction amount.")
    created_at: datetime = Field(description="When the transaction was recorded, in UTC.")


class TransferResponse(BaseModel):
    transfer_id: str = Field(
        description="The identifier correlating the paired source/target transactions for this transfer."
    )
    source_transaction: TransactionResponse = Field(description="The `WITHDRAWAL` recorded on the source account.")
    target_transaction: TransactionResponse = Field(description="The `DEPOSIT` recorded on the target account.")


class GetAccountResponse(BaseModel):
    account_id: str = Field(description="The unique identifier of the account.")
    owner_id: str = Field(description="The `user_id` of the authenticated requester who owns this account.")
    email: str = Field(description="The account owner's email address.")
    balance: MoneySchema = Field(description="The account's current balance.")
    status: str = Field(description="The account's lifecycle status (`ACTIVE`, `SUSPENDED`, or `CLOSED`).")
    created_at: datetime = Field(description="When the account was created, in UTC.")
    updated_at: datetime = Field(description="When the account was last modified, in UTC.")


class TransactionSummaryResponse(BaseModel):
    transaction_id: str = Field(description="The unique identifier of this transaction.")
    type: str = Field(description="The transaction type (`DEPOSIT`, `WITHDRAWAL`, or `INTEREST`).")
    amount: MoneySchema = Field(description="The transaction amount.")
    created_at: datetime = Field(description="When the transaction was recorded, in UTC.")


class GetTransactionsResponse(BaseModel):
    transactions: list[TransactionSummaryResponse] = Field(
        description="The account's transactions, newest first, for the requested page."
    )
    count: int = Field(description="The total number of transactions across all pages, not just this page's size.")
