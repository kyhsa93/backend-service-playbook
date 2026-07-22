from __future__ import annotations

from datetime import datetime

from sqlalchemy import CHAR
from sqlalchemy.orm import Mapped, mapped_column

from ..account.infrastructure.persistence.account_repository import Base


class TaskOutboxModel(Base):
    """The Task Outbox — a table conceptually separate from the Domain Event's
    `OutboxModel` (src/outbox/outbox_model.py). Whereas a Domain Event carries "a fact
    (past): X happened," a Task carries "a command (imperative): perform X" (see "Task
    Queue vs Domain Event" in domain-events.md). Since this is the point the Scheduler
    (Cron) calls with no transaction context, a single row insert into this table itself
    provides atomicity (see "The Task Outbox pattern" in scheduling.md).
    """

    __tablename__ = "task_outbox"

    task_id: Mapped[str] = mapped_column(CHAR(32), primary_key=True)
    task_type: Mapped[str]
    payload: Mapped[str]
    # The FIFO Task queue's parallelism boundary — the same group_id is processed
    # sequentially (see "The MessageGroupId strategy" in scheduling.md). Both of this
    # repository's batches are global Cron batches, so taskType itself is used as the group.
    group_id: Mapped[str]
    # Date-based dedup — even if multiple instances enqueue at the same time on the same
    # day (or month), only 1 row gets in within the FIFO queue's 5-minute dedup window (see
    # "Cron multi-instance safety" in scheduling.md).
    deduplication_id: Mapped[str]
    processed: Mapped[bool] = mapped_column(default=False)
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
