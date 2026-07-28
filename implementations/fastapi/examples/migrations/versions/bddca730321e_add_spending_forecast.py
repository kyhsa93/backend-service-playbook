"""add spending forecast

Revision ID: bddca730321e
Revises: d2e5b8f1a3c6
Create Date: 2026-07-28 00:00:00.000000

"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "bddca730321e"
down_revision: Union[str, Sequence[str], None] = "d2e5b8f1a3c6"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # The read-model table account.forecast-spending's ETL writes to, one row per
    # (account_id, forecast_month) — the unique constraint is the idempotency backstop, the
    # same role as spending_analysis's (account_id, analysis_month) constraint.
    op.create_table(
        "spending_forecast",
        sa.Column("forecast_id", sa.CHAR(length=32), nullable=False),
        sa.Column("account_id", sa.String(), nullable=False),
        sa.Column("forecast_month", sa.String(), nullable=False),
        sa.Column("predicted_amount", sa.Integer(), nullable=False),
        sa.Column("confidence", sa.String(), nullable=False),
        sa.Column("history_months_used", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint("forecast_id"),
        sa.UniqueConstraint("account_id", "forecast_month", name="uq_spending_forecast_account_id_forecast_month"),
    )
    op.create_index(op.f("ix_spending_forecast_account_id"), "spending_forecast", ["account_id"], unique=False)


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(op.f("ix_spending_forecast_account_id"), table_name="spending_forecast")
    op.drop_table("spending_forecast")
