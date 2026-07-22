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
    # The Level 1 idempotency marker for the regular interest-payment batch (scheduling.md)
    # — see Account.apply_interest().
    op.add_column("accounts", sa.Column("last_interest_paid_at", sa.Date(), nullable=True))
    # The Level 1 idempotency marker for the monthly card-statement delivery batch
    # — see Card.send_statement().
    op.add_column("cards", sa.Column("last_statement_sent_month", sa.String(), nullable=True))

    # The Task Outbox — a table conceptually separate from the Domain Event's outbox table
    # (see "Task Queue vs Domain Event" in domain-events.md). See src/task_queue/task_outbox_model.py.
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

    # A Card-BC-specific send-history Ledger — the same role as sent_emails (Account).
    # See src/card/infrastructure/notification/sent_statement_email_model.py.
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
