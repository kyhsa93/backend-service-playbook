from datetime import datetime

from sqlalchemy.orm import Mapped, mapped_column

from ..persistence.account_repository import Base


class SentEmailModel(Base):
    __tablename__ = "sent_emails"

    sent_email_id: Mapped[str] = mapped_column(primary_key=True)
    account_id: Mapped[str]
    event_type: Mapped[str]
    recipient: Mapped[str]
    subject: Mapped[str]
    ses_message_id: Mapped[str]
    # The Outbox row's event_id — the Ledger key used to decide whether this event has
    # already been processed (see "Event Handler Idempotency" in domain-events.md).
    # Nullable for backward compatibility with rows loaded before this migration.
    outbox_event_id: Mapped[str | None] = mapped_column(nullable=True, index=True, default=None)
    sent_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
