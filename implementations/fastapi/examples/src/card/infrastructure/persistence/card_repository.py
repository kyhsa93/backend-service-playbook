from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import Mapped, mapped_column

from ....account.infrastructure.persistence.account_repository import Base
from ...domain.card import Card
from ...domain.card_status import CardStatus
from ...domain.repository import CardRepository


class CardModel(Base):
    __tablename__ = "cards"

    id: Mapped[str] = mapped_column(primary_key=True)
    account_id: Mapped[str]
    owner_id: Mapped[str]
    brand: Mapped[str]
    status: Mapped[str]
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(default=datetime.utcnow, onupdate=datetime.utcnow)
    deleted_at: Mapped[datetime | None] = mapped_column(nullable=True, default=None)


class SqlAlchemyCardRepository(CardRepository):
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def find_cards(
        self,
        page: int,
        take: int,
        card_id: str | None = None,
        owner_id: str | None = None,
        account_id: str | None = None,
        status: list[str] | None = None,
    ) -> tuple[list[Card], int]:
        stmt = select(CardModel).where(CardModel.deleted_at.is_(None))
        count_stmt = select(func.count()).select_from(CardModel).where(CardModel.deleted_at.is_(None))

        if card_id:
            stmt = stmt.where(CardModel.id == card_id)
            count_stmt = count_stmt.where(CardModel.id == card_id)
        if owner_id:
            stmt = stmt.where(CardModel.owner_id == owner_id)
            count_stmt = count_stmt.where(CardModel.owner_id == owner_id)
        if account_id:
            stmt = stmt.where(CardModel.account_id == account_id)
            count_stmt = count_stmt.where(CardModel.account_id == account_id)
        if status:
            stmt = stmt.where(CardModel.status.in_(status))
            count_stmt = count_stmt.where(CardModel.status.in_(status))

        total = (await self._session.execute(count_stmt)).scalar_one()
        rows = (
            (await self._session.execute(stmt.order_by(CardModel.id.desc()).offset(page * take).limit(take)))
            .scalars()
            .all()
        )

        return [self._to_domain(row) for row in rows], total

    async def save_card(self, card: Card) -> None:
        existing = await self._session.get(CardModel, card.card_id)
        if existing:
            existing.status = card.status.value
            existing.updated_at = datetime.utcnow()
        else:
            self._session.add(
                CardModel(
                    id=card.card_id,
                    account_id=card.account_id,
                    owner_id=card.owner_id,
                    brand=card.brand,
                    status=card.status.value,
                    created_at=card.created_at,
                )
            )
        await self._session.flush()

    def _to_domain(self, row: CardModel) -> Card:
        return Card(
            card_id=row.id,
            account_id=row.account_id,
            owner_id=row.owner_id,
            brand=row.brand,
            status=CardStatus(row.status),
            created_at=row.created_at,
        )
