from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from ...domain.account import Account
from ...domain.account_status import AccountStatus
from ...domain.money import Money
from ...domain.repository import AccountRepository
from ...domain.transaction import Transaction


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
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(default=datetime.utcnow, onupdate=datetime.utcnow)
    deleted_at: Mapped[datetime | None] = mapped_column(nullable=True, default=None)


class TransactionModel(Base):
    __tablename__ = "transactions"

    id: Mapped[str] = mapped_column(primary_key=True)
    account_id: Mapped[str]
    type: Mapped[str]
    amount: Mapped[int]
    currency: Mapped[str]
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
    # Payment BC의 Integration Event 반응이 남긴 거래를 상관관계 짓고(payment_id/refund_id),
    # 동일 (reference_id, type) 조합의 재처리를 막는 Level 2 Ledger 키로도 쓰인다
    # (has_transaction_with_reference 참고). 사용자가 직접 요청한 입출금에는 없다(nullable).
    reference_id: Mapped[str | None] = mapped_column(nullable=True, index=True, default=None)


class SqlAlchemyAccountRepository(AccountRepository):
    def __init__(self, session: AsyncSession) -> None:
        # 지연 import — outbox_model.py가 이 모듈의 Base를 import하므로, 모듈 최상단에서
        # OutboxWriter를 import하면 순환 참조가 발생한다 (module-pattern.md "Python의 순환 참조" 참조).
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
            existing.updated_at = datetime.utcnow()
        else:
            self._session.add(
                AccountModel(
                    id=account.account_id,
                    owner_id=account.owner_id,
                    email=account.email,
                    amount=account.balance.amount,
                    currency=account.balance.currency,
                    status=account.status.value,
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
                )
            )

        events = account.pull_events()
        if events:
            await self._outbox_writer.save_all(events)

        await self._session.flush()

    async def find_transactions(self, account_id: str, page: int, take: int) -> tuple[list[Transaction], int]:
        stmt = select(TransactionModel).where(TransactionModel.account_id == account_id)
        count_stmt = select(func.count()).select_from(TransactionModel).where(TransactionModel.account_id == account_id)

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

    def _to_domain(self, row: AccountModel) -> Account:
        return Account(
            account_id=row.id,
            owner_id=row.owner_id,
            email=row.email,
            balance=Money(row.amount, row.currency),
            status=AccountStatus(row.status),
            created_at=row.created_at,
            updated_at=row.updated_at,
        )
