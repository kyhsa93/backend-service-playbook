"""create payments and refunds tables, add reference_id to transactions

Revision ID: 450018060f59
Revises: 505193f55fdd
Create Date: 2026-07-18 20:33:38.894341

"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "450018060f59"
down_revision: Union[str, Sequence[str], None] = "505193f55fdd"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        "payments",
        sa.Column("id", sa.String(), nullable=False),
        sa.Column("card_id", sa.String(), nullable=False),
        sa.Column("account_id", sa.String(), nullable=False),
        sa.Column("owner_id", sa.String(), nullable=False),
        sa.Column("amount", sa.Integer(), nullable=False),
        sa.Column("status", sa.String(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_table(
        "refunds",
        sa.Column("id", sa.String(), nullable=False),
        sa.Column("payment_id", sa.String(), nullable=False),
        sa.Column("amount", sa.Integer(), nullable=False),
        sa.Column("reason", sa.String(), nullable=False),
        sa.Column("status", sa.String(), nullable=False),
        sa.Column("decision_note", sa.String(), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint("id"),
    )
    # Correlates a transaction left by the Payment BC's Integration Event reactions
    # (withdraw-by-payment/deposit-by-payment), and also serves as the Level 2 Ledger key
    # that prevents reprocessing the same (reference_id, type) combination (see
    # has_transaction_with_reference in account_repository.py).
    op.add_column("transactions", sa.Column("reference_id", sa.String(), nullable=True))
    op.create_index(op.f("ix_transactions_reference_id"), "transactions", ["reference_id"], unique=False)


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(op.f("ix_transactions_reference_id"), table_name="transactions")
    op.drop_column("transactions", "reference_id")
    op.drop_table("refunds")
    op.drop_table("payments")
