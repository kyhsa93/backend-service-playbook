"""add deleted_at to payments and refunds

Revision ID: 6a1f0d3c9b21
Revises: 450018060f59
Create Date: 2026-07-21 00:00:00.000000

"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "6a1f0d3c9b21"
down_revision: Union[str, Sequence[str], None] = "450018060f59"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # PaymentModel/RefundModel are Entities whose status changes (i.e. mutable state), yet
    # they had no deleted_at — a gap that violated persistence.md's "every Entity with
    # mutable state gets created_at/updated_at/deleted_at" principle (AccountModel/CardModel
    # already had it). This reflects what the soft-delete-filter harness rule caught.
    op.add_column("payments", sa.Column("deleted_at", sa.DateTime(), nullable=True))
    op.add_column("refunds", sa.Column("deleted_at", sa.DateTime(), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column("refunds", "deleted_at")
    op.drop_column("payments", "deleted_at")
