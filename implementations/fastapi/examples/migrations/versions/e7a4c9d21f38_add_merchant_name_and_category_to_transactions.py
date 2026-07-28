"""add merchant_name and category to transactions

Revision ID: e7a4c9d21f38
Revises: bddca730321e
Create Date: 2026-07-28 00:00:00.000000

"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "e7a4c9d21f38"
down_revision: Union[str, Sequence[str], None] = "bddca730321e"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Both columns nullable: merchant_name is only ever set for a withdrawal the requester
    # chose to attach one to, and category is filled in later, asynchronously, by
    # CategorizeTransactionEventHandler.
    op.add_column("transactions", sa.Column("merchant_name", sa.String(), nullable=True))
    op.add_column("transactions", sa.Column("category", sa.String(), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column("transactions", "category")
    op.drop_column("transactions", "merchant_name")
