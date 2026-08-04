from unittest.mock import AsyncMock

import pytest

from src.account.application.event.categorize_transaction_event_handler import CategorizeTransactionEventHandler
from src.account.domain.money import Money
from src.account.domain.transaction import Transaction
from src.common.clock import utc_now


@pytest.fixture
def categorizer() -> AsyncMock:
    return AsyncMock()


@pytest.fixture
def transaction_repo() -> AsyncMock:
    return AsyncMock()


def make_transaction() -> Transaction:
    return Transaction(
        transaction_id="transaction-1",
        account_id="account-1",
        type="WITHDRAWAL",
        amount=Money(5500, "KRW"),
        created_at=utc_now(),
        merchant_name="Starbucks Gangnam",
    )


@pytest.mark.asyncio
async def test_handle_when_the_event_has_a_merchant_name_then_categorizes_and_saves_it(
    categorizer: AsyncMock, transaction_repo: AsyncMock
) -> None:
    transaction = make_transaction()
    transaction_repo.find_transaction.return_value = transaction
    categorizer.categorize.return_value = "FOOD"
    handler = CategorizeTransactionEventHandler(categorizer, transaction_repo)

    await handler.handle(
        {
            "transaction_id": "transaction-1",
            "amount": {"amount": 5500, "currency": "KRW"},
            "merchant_name": "Starbucks Gangnam",
        }
    )

    categorizer.categorize.assert_awaited_once_with("Starbucks Gangnam", 5500)
    saved = transaction_repo.save_transaction.await_args.args[0]
    assert saved.transaction_id == "transaction-1"
    assert saved.category == "FOOD"


@pytest.mark.asyncio
async def test_handle_when_the_event_has_no_merchant_name_then_skips_categorization_entirely(
    categorizer: AsyncMock, transaction_repo: AsyncMock
) -> None:
    handler = CategorizeTransactionEventHandler(categorizer, transaction_repo)

    await handler.handle({"transaction_id": "transaction-1", "amount": {"amount": 5500, "currency": "KRW"}})

    transaction_repo.find_transaction.assert_not_awaited()
    categorizer.categorize.assert_not_awaited()
    transaction_repo.save_transaction.assert_not_awaited()


@pytest.mark.asyncio
async def test_handle_when_the_transaction_no_longer_exists_then_skips_categorization_without_raising(
    categorizer: AsyncMock, transaction_repo: AsyncMock
) -> None:
    transaction_repo.find_transaction.return_value = None
    handler = CategorizeTransactionEventHandler(categorizer, transaction_repo)

    await handler.handle(
        {
            "transaction_id": "transaction-1",
            "amount": {"amount": 5500, "currency": "KRW"},
            "merchant_name": "Starbucks Gangnam",
        }
    )

    categorizer.categorize.assert_not_awaited()
    transaction_repo.save_transaction.assert_not_awaited()
