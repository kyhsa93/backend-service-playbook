from src.account.domain.money import Money
from src.account.domain.transaction import Transaction


def make_withdrawal(merchant_name: str | None = None) -> Transaction:
    return Transaction.create(
        account_id="account-1", type="WITHDRAWAL", amount=Money(5500, "KRW"), merchant_name=merchant_name
    )


def test_categorize_returns_a_new_instance_with_the_category_set() -> None:
    transaction = make_withdrawal(merchant_name="Starbucks Gangnam")

    categorized = transaction.categorize("FOOD")

    assert categorized.category == "FOOD"
    assert categorized is not transaction
    # The original instance is untouched — Transaction is immutable.
    assert transaction.category is None


def test_categorize_preserves_every_other_field() -> None:
    transaction = make_withdrawal(merchant_name="Starbucks Gangnam")

    categorized = transaction.categorize("FOOD")

    assert categorized.transaction_id == transaction.transaction_id
    assert categorized.account_id == transaction.account_id
    assert categorized.type == transaction.type
    assert categorized.amount == transaction.amount
    assert categorized.merchant_name == transaction.merchant_name
    assert categorized.created_at == transaction.created_at
