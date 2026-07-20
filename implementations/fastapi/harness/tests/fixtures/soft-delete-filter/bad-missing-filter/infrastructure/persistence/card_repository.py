from datetime import datetime

from sqlalchemy import select
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class CardModel(Base):
    __tablename__ = "cards"

    id: Mapped[str] = mapped_column(primary_key=True)
    status: Mapped[str]
    created_at: Mapped[datetime]
    updated_at: Mapped[datetime]
    deleted_at: Mapped[datetime | None] = mapped_column(nullable=True, default=None)


class SqlAlchemyCardRepository:
    async def find_cards(self, page: int, take: int):
        stmt = select(CardModel)
        return stmt
