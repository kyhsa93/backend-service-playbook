from datetime import datetime

from sqlalchemy import select
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class PaymentModel(Base):
    __tablename__ = "payments"

    id: Mapped[str] = mapped_column(primary_key=True)
    status: Mapped[str]
    created_at: Mapped[datetime]
    updated_at: Mapped[datetime]


class SqlAlchemyPaymentRepository:
    async def find_payments(self, page: int, take: int):
        stmt = select(PaymentModel)
        return stmt
