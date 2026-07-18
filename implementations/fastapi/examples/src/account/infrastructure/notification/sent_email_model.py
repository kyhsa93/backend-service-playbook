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
    # Outbox 행의 event_id — 이 이벤트가 이미 처리됐는지 판단하는 Ledger 키(domain-events.md
    # "이벤트 핸들러 멱등성" 참고). 마이그레이션 이전에 적재된 행과의 하위 호환을 위해 nullable.
    outbox_event_id: Mapped[str | None] = mapped_column(nullable=True, index=True, default=None)
    sent_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
