from sqlalchemy.ext.asyncio import AsyncSession

from ...domain.money import Money
from ...domain.transaction import Transaction
from ...domain.transaction_repository import TransactionRepository
from .account_repository import TransactionModel


class SqlAlchemyTransactionRepository(TransactionRepository):
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def find_transaction(self, transaction_id: str) -> Transaction | None:
        row = await self._session.get(TransactionModel, transaction_id)
        if row is None:
            return None

        return Transaction(
            transaction_id=row.id,
            account_id=row.account_id,
            type=row.type,  # type: ignore[arg-type]
            amount=Money(row.amount, row.currency),
            created_at=row.created_at,
            reference_id=row.reference_id,
            merchant_name=row.merchant_name,
            category=row.category,  # type: ignore[arg-type]
        )

    async def save_transaction(self, transaction: Transaction) -> None:
        row = await self._session.get(TransactionModel, transaction.transaction_id)
        if row is None:
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
                    category=transaction.category,
                )
            )
        else:
            row.merchant_name = transaction.merchant_name
            row.category = transaction.category

        await self._session.flush()
