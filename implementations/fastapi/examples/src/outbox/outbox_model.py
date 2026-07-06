from __future__ import annotations

from datetime import datetime

from sqlalchemy import CHAR
from sqlalchemy.orm import Mapped, mapped_column

from ..account.infrastructure.persistence.account_repository import Base


class OutboxModel(Base):
    __tablename__ = "outbox"

    event_id: Mapped[str] = mapped_column(CHAR(32), primary_key=True)
    event_type: Mapped[str]
    payload: Mapped[str]
    processed: Mapped[bool] = mapped_column(default=False)
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
