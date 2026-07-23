"""add trace_parent to outbox

Revision ID: c1d9a3f7e2b4
Revises: 8f1c2a4e6d90
Create Date: 2026-07-23 00:00:00.000000

"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "c1d9a3f7e2b4"
down_revision: Union[str, Sequence[str], None] = "8f1c2a4e6d90"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column("outbox", sa.Column("trace_parent", sa.String(), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column("outbox", "trace_parent")
