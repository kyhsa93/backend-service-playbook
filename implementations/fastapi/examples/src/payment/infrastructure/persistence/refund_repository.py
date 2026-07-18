from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import Mapped, mapped_column

from ....account.infrastructure.persistence.account_repository import Base
from ...domain.refund import Refund
from ...domain.refund_repository import RefundRepository
from ...domain.refund_status import RefundStatus


class RefundModel(Base):
    __tablename__ = "refunds"

    id: Mapped[str] = mapped_column(primary_key=True)
    payment_id: Mapped[str]
    amount: Mapped[int]
    reason: Mapped[str]
    status: Mapped[str]
    decision_note: Mapped[str | None] = mapped_column(nullable=True, default=None)
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(default=datetime.utcnow, onupdate=datetime.utcnow)


class SqlAlchemyRefundRepository(RefundRepository):
    def __init__(self, session: AsyncSession) -> None:
        from ....outbox.outbox_writer import OutboxWriter

        self._session = session
        self._outbox_writer = OutboxWriter(session)

    async def find_refunds(
        self,
        page: int,
        take: int,
        refund_id: str | None = None,
        payment_id: str | None = None,
        status: list[str] | None = None,
    ) -> tuple[list[Refund], int]:
        stmt = select(RefundModel)
        count_stmt = select(func.count()).select_from(RefundModel)

        if refund_id:
            stmt = stmt.where(RefundModel.id == refund_id)
            count_stmt = count_stmt.where(RefundModel.id == refund_id)
        if payment_id:
            stmt = stmt.where(RefundModel.payment_id == payment_id)
            count_stmt = count_stmt.where(RefundModel.payment_id == payment_id)
        if status:
            stmt = stmt.where(RefundModel.status.in_(status))
            count_stmt = count_stmt.where(RefundModel.status.in_(status))

        total = (await self._session.execute(count_stmt)).scalar_one()
        rows = (
            (await self._session.execute(stmt.order_by(RefundModel.created_at.desc()).offset(page * take).limit(take)))
            .scalars()
            .all()
        )

        return [self._to_domain(row) for row in rows], total

    async def save(self, refund: Refund) -> None:
        existing = await self._session.get(RefundModel, refund.refund_id)
        if existing:
            existing.status = refund.status.value
            existing.decision_note = refund.decision_note
            existing.updated_at = datetime.utcnow()
        else:
            self._session.add(
                RefundModel(
                    id=refund.refund_id,
                    payment_id=refund.payment_id,
                    amount=refund.amount,
                    reason=refund.reason,
                    status=refund.status.value,
                    decision_note=refund.decision_note,
                    created_at=refund.created_at,
                )
            )

        events = refund.pull_events()
        if events:
            await self._outbox_writer.save_all(events)

        await self._session.flush()

    def _to_domain(self, row: RefundModel) -> Refund:
        return Refund(
            refund_id=row.id,
            payment_id=row.payment_id,
            amount=row.amount,
            reason=row.reason,
            status=RefundStatus(row.status),
            decision_note=row.decision_note,
            created_at=row.created_at,
        )
