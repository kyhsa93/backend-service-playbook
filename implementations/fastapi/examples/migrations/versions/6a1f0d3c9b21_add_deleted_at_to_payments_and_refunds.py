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
    # PaymentModel/RefundModel은 status가 바뀌는(=상태 변경 가능한) Entity인데도 deleted_at이
    # 없었다 — persistence.md "모든 상태 변경 가능한 Entity에 created_at/updated_at/deleted_at을
    # 둔다" 원칙에 어긋나는 격차였다(AccountModel/CardModel은 이미 갖고 있었음). soft-delete-filter
    # harness 규칙이 잡아낸 것을 반영한다.
    op.add_column("payments", sa.Column("deleted_at", sa.DateTime(), nullable=True))
    op.add_column("refunds", sa.Column("deleted_at", sa.DateTime(), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column("refunds", "deleted_at")
    op.drop_column("payments", "deleted_at")
