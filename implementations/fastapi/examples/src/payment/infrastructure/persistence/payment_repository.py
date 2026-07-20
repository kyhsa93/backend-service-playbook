from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import Mapped, mapped_column

from ....account.infrastructure.persistence.account_repository import Base
from ...domain.payment import Payment
from ...domain.payment_repository import PaymentRepository
from ...domain.payment_status import PaymentStatus


class PaymentModel(Base):
    __tablename__ = "payments"

    id: Mapped[str] = mapped_column(primary_key=True)
    card_id: Mapped[str]
    account_id: Mapped[str]
    owner_id: Mapped[str]
    amount: Mapped[int]
    status: Mapped[str]
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(default=datetime.utcnow, onupdate=datetime.utcnow)


class SqlAlchemyPaymentRepository(PaymentRepository):
    def __init__(self, session: AsyncSession) -> None:
        # 지연 import — outbox_model.py가 account_repository.py의 Base를 import하므로,
        # 모듈 최상단에서 OutboxWriter를 import하면 순환 참조가 발생한다
        # (module-pattern.md "Python의 순환 참조" 참조. account_repository.py와 동일한 처리).
        from ....outbox.outbox_writer import OutboxWriter

        self._session = session
        self._outbox_writer = OutboxWriter(session)

    async def find_payments(
        self,
        page: int,
        take: int,
        payment_id: str | None = None,
        owner_id: str | None = None,
        card_id: str | None = None,
        account_id: str | None = None,
        status: list[str] | None = None,
    ) -> tuple[list[Payment], int]:
        stmt = select(PaymentModel)
        count_stmt = select(func.count()).select_from(PaymentModel)

        if payment_id:
            stmt = stmt.where(PaymentModel.id == payment_id)
            count_stmt = count_stmt.where(PaymentModel.id == payment_id)
        if owner_id:
            stmt = stmt.where(PaymentModel.owner_id == owner_id)
            count_stmt = count_stmt.where(PaymentModel.owner_id == owner_id)
        if card_id:
            stmt = stmt.where(PaymentModel.card_id == card_id)
            count_stmt = count_stmt.where(PaymentModel.card_id == card_id)
        if account_id:
            stmt = stmt.where(PaymentModel.account_id == account_id)
            count_stmt = count_stmt.where(PaymentModel.account_id == account_id)
        if status:
            stmt = stmt.where(PaymentModel.status.in_(status))
            count_stmt = count_stmt.where(PaymentModel.status.in_(status))

        total = (await self._session.execute(count_stmt)).scalar_one()
        rows = (
            (await self._session.execute(stmt.order_by(PaymentModel.id.desc()).offset(page * take).limit(take)))
            .scalars()
            .all()
        )

        return [self._to_domain(row) for row in rows], total

    async def save_payment(self, payment: Payment) -> None:
        existing = await self._session.get(PaymentModel, payment.payment_id)
        if existing:
            existing.status = payment.status.value
            existing.updated_at = datetime.utcnow()
        else:
            self._session.add(
                PaymentModel(
                    id=payment.payment_id,
                    card_id=payment.card_id,
                    account_id=payment.account_id,
                    owner_id=payment.owner_id,
                    amount=payment.amount,
                    status=payment.status.value,
                    created_at=payment.created_at,
                )
            )

        events = payment.pull_events()
        if events:
            await self._outbox_writer.save_all(events)

        await self._session.flush()

    def _to_domain(self, row: PaymentModel) -> Payment:
        return Payment(
            payment_id=row.id,
            card_id=row.card_id,
            account_id=row.account_id,
            owner_id=row.owner_id,
            amount=row.amount,
            status=PaymentStatus(row.status),
            created_at=row.created_at,
        )
