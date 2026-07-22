from datetime import datetime

from sqlalchemy.orm import Mapped, mapped_column

from ....account.infrastructure.persistence.account_repository import Base


class SentStatementEmailModel(Base):
    """A Ledger with the same role as `account/infrastructure/notification/sent_email_model.py`
    (SentEmailModel) — records the Level 2 idempotency (preventing duplicate sends based on
    outbox_event_id) of the CardStatementSent event. Split into a separate table since it's
    a Card-BC-specific send history."""

    __tablename__ = "sent_statement_emails"

    sent_email_id: Mapped[str] = mapped_column(primary_key=True)
    card_id: Mapped[str]
    event_type: Mapped[str]
    recipient: Mapped[str]
    subject: Mapped[str]
    ses_message_id: Mapped[str]
    outbox_event_id: Mapped[str] = mapped_column(index=True)
    sent_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
