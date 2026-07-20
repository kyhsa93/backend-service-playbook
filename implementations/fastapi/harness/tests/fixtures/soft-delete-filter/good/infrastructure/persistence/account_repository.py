from datetime import datetime

from sqlalchemy import select
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class AccountModel(Base):
    __tablename__ = "accounts"

    id: Mapped[str] = mapped_column(primary_key=True)
    status: Mapped[str]
    created_at: Mapped[datetime]
    updated_at: Mapped[datetime]
    deleted_at: Mapped[datetime | None] = mapped_column(nullable=True, default=None)


class TransactionModel(Base):
    __tablename__ = "transactions"

    id: Mapped[str] = mapped_column(primary_key=True)
    account_id: Mapped[str]
    created_at: Mapped[datetime]


class SqlAlchemyAccountRepository:
    async def find_accounts(self, page: int, take: int):
        stmt = select(AccountModel).where(AccountModel.deleted_at.is_(None))
        return stmt

    async def find_transactions(self, account_id: str):
        stmt = select(TransactionModel).where(TransactionModel.account_id == account_id)
        return stmt
