"""add scheduling task queue tables

Revision ID: 8f1c2a4e6d90
Revises: 6a1f0d3c9b21
Create Date: 2026-07-21 00:00:00.000000

"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "8f1c2a4e6d90"
down_revision: Union[str, Sequence[str], None] = "6a1f0d3c9b21"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # 정기 이자 지급 배치(scheduling.md)의 Level 1 멱등성 마커 — Account.apply_interest() 참고.
    op.add_column("accounts", sa.Column("last_interest_paid_at", sa.Date(), nullable=True))
    # 매월 카드 사용내역 발송 배치의 Level 1 멱등성 마커 — Card.send_statement() 참고.
    op.add_column("cards", sa.Column("last_statement_sent_month", sa.String(), nullable=True))

    # Task Outbox — Domain Event의 outbox 테이블과 개념적으로 분리된 테이블
    # (domain-events.md "Task Queue vs Domain Event"). src/task_queue/task_outbox_model.py 참고.
    op.create_table(
        "task_outbox",
        sa.Column("task_id", sa.CHAR(length=32), nullable=False),
        sa.Column("task_type", sa.String(), nullable=False),
        sa.Column("payload", sa.String(), nullable=False),
        sa.Column("group_id", sa.String(), nullable=False),
        sa.Column("deduplication_id", sa.String(), nullable=False),
        sa.Column("processed", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint("task_id"),
    )

    # Card BC 전용 발송 이력 Ledger — sent_emails(Account)와 동일한 역할.
    # src/card/infrastructure/notification/sent_statement_email_model.py 참고.
    op.create_table(
        "sent_statement_emails",
        sa.Column("sent_email_id", sa.String(), nullable=False),
        sa.Column("card_id", sa.String(), nullable=False),
        sa.Column("event_type", sa.String(), nullable=False),
        sa.Column("recipient", sa.String(), nullable=False),
        sa.Column("subject", sa.String(), nullable=False),
        sa.Column("ses_message_id", sa.String(), nullable=False),
        sa.Column("outbox_event_id", sa.String(), nullable=False),
        sa.Column("sent_at", sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint("sent_email_id"),
    )
    op.create_index(
        op.f("ix_sent_statement_emails_outbox_event_id"), "sent_statement_emails", ["outbox_event_id"], unique=False
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(op.f("ix_sent_statement_emails_outbox_event_id"), table_name="sent_statement_emails")
    op.drop_table("sent_statement_emails")
    op.drop_table("task_outbox")
    op.drop_column("cards", "last_statement_sent_month")
    op.drop_column("accounts", "last_interest_paid_at")
