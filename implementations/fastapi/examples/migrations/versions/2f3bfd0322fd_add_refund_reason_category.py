"""add refund_reason_category to refunds

Revision ID: 2f3bfd0322fd
Revises: e7a4c9d21f38
Create Date: 2026-07-28 00:00:00.000000

"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "2f3bfd0322fd"
down_revision: Union[str, Sequence[str], None] = "e7a4c9d21f38"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Nullable — filled in asynchronously, after the refund is created, by
    # ClassifyRefundReasonEventHandler reacting to RefundRequested.
    op.add_column("refunds", sa.Column("reason_category", sa.String(), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column("refunds", "reason_category")
