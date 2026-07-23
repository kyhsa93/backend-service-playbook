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
    # The W3C `traceparent` of whatever span was active when this row was written (usually
    # the HTTP request's span) — carried the same way `eventType`/`event_id` already are:
    # written here by OutboxWriter, forwarded as an SQS message attribute by OutboxPoller,
    # and re-hydrated into a child span by OutboxConsumer, so the HTTP request and its async
    # event processing land in one trace (observability.md). Nullable: a row written outside
    # any active span (there is none, e.g. a unit test constructing events directly) simply
    # carries no trace link — the event is still processed normally.
    trace_parent: Mapped[str | None] = mapped_column(default=None)
