from datetime import date, datetime, time

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from ....common.clock import utc_now
from ...domain.account import Account
from ...domain.account_status import AccountStatus
from ...domain.money import Money
from ...domain.repository import AccountRepository
from ...domain.transaction import Transaction, TransactionType


class Base(DeclarativeBase):
    pass


class AccountModel(Base):
    __tablename__ = "accounts"

    id: Mapped[str] = mapped_column(primary_key=True)
    owner_id: Mapped[str]
    email: Mapped[str]
    amount: Mapped[int]
    currency: Mapped[str]
    status: Mapped[str]
    created_at: Mapped[datetime] = mapped_column(default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(default=utc_now, onupdate=utc_now)
    deleted_at: Mapped[datetime | None] = mapped_column(nullable=True, default=None)
    # The Level 1 idempotency marker for the regular interest-payment batch — see Account.apply_interest().
    last_interest_paid_at: Mapped[date | None] = mapped_column(nullable=True, default=None)


class TransactionModel(Base):
    __tablename__ = "transactions"

    id: Mapped[str] = mapped_column(primary_key=True)
    account_id: Mapped[str]
    type: Mapped[str]
    amount: Mapped[int]
    currency: Mapped[str]
    created_at: Mapped[datetime] = mapped_column(default=utc_now)
    # Correlates a transaction left by a reaction to the Payment BC's Integration Event
    # (payment_id/refund_id), and also serves as the Level 2 Ledger key that prevents
    # reprocessing the same (reference_id, type) combination (see
    # has_transaction_with_reference). Absent (nullable) for a deposit/withdrawal the user
    # requested directly.
    reference_id: Mapped[str | None] = mapped_column(nullable=True, index=True, default=None)
    # The payee/memo optionally attached to a withdrawal at request time — see transaction.py.
    merchant_name: Mapped[str | None] = mapped_column(nullable=True, default=None)
    # Filled in asynchronously by CategorizeTransactionEventHandler — null until that
    # reaction runs (or forever, for a transaction with no merchant_name to classify).
    category: Mapped[str | None] = mapped_column(nullable=True, default=None)


class SqlAlchemyAccountRepository(AccountRepository):
    def __init__(self, session: AsyncSession) -> None:
        # deferred import — since outbox_model.py imports this module's Base, importing
        # OutboxWriter at the module's top level would create a circular import (see
        # "Python's circular imports" in module-pattern.md).
        from ....outbox.outbox_writer import OutboxWriter

        self._session = session
        self._outbox_writer = OutboxWriter(session)

    async def find_accounts(
        self,
        page: int,
        take: int,
        account_id: str | None = None,
        owner_id: str | None = None,
        status: list[str] | None = None,
    ) -> tuple[list[Account], int]:
        stmt = select(AccountModel).where(AccountModel.deleted_at.is_(None))
        count_stmt = select(func.count()).select_from(AccountModel).where(AccountModel.deleted_at.is_(None))

        if account_id:
            stmt = stmt.where(AccountModel.id == account_id)
            count_stmt = count_stmt.where(AccountModel.id == account_id)
        if owner_id:
            stmt = stmt.where(AccountModel.owner_id == owner_id)
            count_stmt = count_stmt.where(AccountModel.owner_id == owner_id)
        if status:
            stmt = stmt.where(AccountModel.status.in_(status))
            count_stmt = count_stmt.where(AccountModel.status.in_(status))

        total = (await self._session.execute(count_stmt)).scalar_one()
        rows = (
            (await self._session.execute(stmt.order_by(AccountModel.id.desc()).offset(page * take).limit(take)))
            .scalars()
            .all()
        )

        return [self._to_domain(row) for row in rows], total

    async def save_account(self, account: Account) -> None:
        existing = await self._session.get(AccountModel, account.account_id)
        if existing:
            existing.amount = account.balance.amount
            existing.status = account.status.value
            existing.last_interest_paid_at = account.last_interest_paid_at
            existing.updated_at = utc_now()
        else:
            self._session.add(
                AccountModel(
                    id=account.account_id,
                    owner_id=account.owner_id,
                    email=account.email,
                    amount=account.balance.amount,
                    currency=account.balance.currency,
                    status=account.status.value,
                    last_interest_paid_at=account.last_interest_paid_at,
                )
            )

        for transaction in account.pull_pending_transactions():
            self._session.add(
                TransactionModel(
                    id=transaction.transaction_id,
                    account_id=transaction.account_id,
                    type=transaction.type,
                    amount=transaction.amount.amount,
                    currency=transaction.amount.currency,
                    created_at=transaction.created_at,
                    reference_id=transaction.reference_id,
                    merchant_name=transaction.merchant_name,
                )
            )

        events = account.pull_events()
        if events:
            await self._outbox_writer.save_all(events)

        await self._session.flush()

    async def find_transactions(
        self,
        account_id: str,
        page: int,
        take: int,
        type: TransactionType | None = None,
        from_date: date | None = None,
        to_date: date | None = None,
    ) -> tuple[list[Transaction], int]:
        stmt = select(TransactionModel).where(TransactionModel.account_id == account_id)
        count_stmt = select(func.count()).select_from(TransactionModel).where(TransactionModel.account_id == account_id)

        if type:
            stmt = stmt.where(TransactionModel.type == type)
            count_stmt = count_stmt.where(TransactionModel.type == type)
        if from_date:
            from_dt = datetime.combine(from_date, time.min)
            stmt = stmt.where(TransactionModel.created_at >= from_dt)
            count_stmt = count_stmt.where(TransactionModel.created_at >= from_dt)
        if to_date:
            to_dt = datetime.combine(to_date, time.max)
            stmt = stmt.where(TransactionModel.created_at <= to_dt)
            count_stmt = count_stmt.where(TransactionModel.created_at <= to_dt)

        total = (await self._session.execute(count_stmt)).scalar_one()
        rows = (
            (
                await self._session.execute(
                    stmt.order_by(TransactionModel.created_at.desc()).offset(page * take).limit(take)
                )
            )
            .scalars()
            .all()
        )

        transactions = [
            Transaction(
                transaction_id=r.id,
                account_id=r.account_id,
                type=r.type,  # type: ignore[arg-type]
                amount=Money(r.amount, r.currency),
                created_at=r.created_at,
                reference_id=r.reference_id,
                merchant_name=r.merchant_name,
                category=r.category,  # type: ignore[arg-type]
            )
            for r in rows
        ]
        return transactions, total

    async def has_transaction_with_reference(self, reference_id: str, type: str) -> bool:
        stmt = (
            select(func.count())
            .select_from(TransactionModel)
            .where(TransactionModel.reference_id == reference_id, TransactionModel.type == type)
        )
        count = (await self._session.execute(stmt)).scalar_one()
        return count > 0

    async def summarize_transactions(
        self,
        account_id: str,
        type: list[TransactionType],
        created_at_from: datetime,
        created_at_to: datetime,
    ) -> tuple[int, int]:
        stmt = (
            select(func.count(), func.coalesce(func.sum(TransactionModel.amount), 0))
            .select_from(TransactionModel)
            .where(
                TransactionModel.account_id == account_id,
                TransactionModel.type.in_(type),
                TransactionModel.created_at >= created_at_from,
                TransactionModel.created_at < created_at_to,
            )
        )
        count, total_amount = (await self._session.execute(stmt)).one()
        return int(count), int(total_amount)

    def _to_domain(self, row: AccountModel) -> Account:
        return Account(
            account_id=row.id,
            owner_id=row.owner_id,
            email=row.email,
            balance=Money(row.amount, row.currency),
            status=AccountStatus(row.status),
            created_at=row.created_at,
            updated_at=row.updated_at,
            last_interest_paid_at=row.last_interest_paid_at,
        )
