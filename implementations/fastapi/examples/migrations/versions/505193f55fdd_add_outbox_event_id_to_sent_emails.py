"""add outbox_event_id to sent_emails

Revision ID: 505193f55fdd
Revises: 91b47f3aa56d
Create Date: 2026-07-18 00:00:00.000000

"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "505193f55fdd"
down_revision: Union[str, Sequence[str], None] = "91b47f3aa56d"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column("sent_emails", sa.Column("outbox_event_id", sa.String(), nullable=True))
    op.create_index(op.f("ix_sent_emails_outbox_event_id"), "sent_emails", ["outbox_event_id"], unique=False)


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(op.f("ix_sent_emails_outbox_event_id"), table_name="sent_emails")
    op.drop_column("sent_emails", "outbox_event_id")
