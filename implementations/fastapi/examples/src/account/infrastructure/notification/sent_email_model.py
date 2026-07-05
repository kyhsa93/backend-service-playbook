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
    sent_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
