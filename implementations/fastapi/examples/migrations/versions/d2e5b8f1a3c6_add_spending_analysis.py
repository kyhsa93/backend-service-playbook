"""add spending analysis

Revision ID: d2e5b8f1a3c6
Revises: c1d9a3f7e2b4
Create Date: 2026-07-27 00:00:00.000000

"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "d2e5b8f1a3c6"
down_revision: Union[str, Sequence[str], None] = "c1d9a3f7e2b4"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # The read-model table account.analyze-monthly-spending's ETL writes to, one row per
    # (account_id, analysis_month) — the unique constraint is the idempotency backstop, the
    # same role as sent_statement_emails' send-history ledger.
    op.create_table(
        "spending_analysis",
        sa.Column("analysis_id", sa.CHAR(length=32), nullable=False),
        sa.Column("account_id", sa.String(), nullable=False),
        sa.Column("analysis_month", sa.String(), nullable=False),
        sa.Column("total_amount", sa.Integer(), nullable=False),
        sa.Column("transaction_count", sa.Integer(), nullable=False),
        sa.Column("average_amount", sa.Integer(), nullable=False),
        sa.Column("change_from_previous_month", sa.Integer(), nullable=False),
        sa.Column("trend", sa.String(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint("analysis_id"),
        sa.UniqueConstraint("account_id", "analysis_month", name="uq_spending_analysis_account_id_analysis_month"),
    )
    op.create_index(op.f("ix_spending_analysis_account_id"), "spending_analysis", ["account_id"], unique=False)


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(op.f("ix_spending_analysis_account_id"), table_name="spending_analysis")
    op.drop_table("spending_analysis")
