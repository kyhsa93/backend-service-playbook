from datetime import datetime

from sqlalchemy.orm import Mapped, mapped_column

from ....account.infrastructure.persistence.account_repository import Base


class SentStatementEmailModel(Base):
    """`account/infrastructure/notification/sent_email_model.py`(SentEmailModel)와 동일한
    역할의 Ledger — CardStatementSent 이벤트의 Level 2 멱등성(outbox_event_id 기준 중복
    발송 방지)을 기록한다. Card BC 전용 발송 이력이라 별도 테이블로 분리한다."""

    __tablename__ = "sent_statement_emails"

    sent_email_id: Mapped[str] = mapped_column(primary_key=True)
    card_id: Mapped[str]
    event_type: Mapped[str]
    recipient: Mapped[str]
    subject: Mapped[str]
    ses_message_id: Mapped[str]
    outbox_event_id: Mapped[str] = mapped_column(index=True)
    sent_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
