from datetime import date, datetime
from unittest.mock import AsyncMock

import pytest

from src.account.application.query.ask_transaction_history_handler import (
    AskTransactionHistoryHandler,
    AskTransactionHistoryQuery,
)
from src.account.application.query.result import MoneyResult, TransactionSummary
from src.account.application.service.nl_transaction_query_translator import TransactionFilter
from src.account.domain.errors import AccountNotFoundError


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()


@pytest.fixture
def translator() -> AsyncMock:
    return AsyncMock()


@pytest.fixture
def composer() -> AsyncMock:
    return AsyncMock()


@pytest.mark.asyncio
async def test_execute_scopes_the_retrieval_to_the_authenticated_requester_regardless_of_the_translated_filter(
    repo, translator, composer
) -> None:
    # The mocked translator returns a filter with a type/date range — the point of this test is
    # that none of that ever influences WHO the retrieval is scoped to: find_accounts is always
    # called with the query's own requester_id, and find_transactions is always scoped by the
    # account_id that ownership check already verified (TransactionFilter has no owner_id field
    # to begin with, but this pins the intent explicitly, mirroring the same guardrail test in
    # the other language ports).
    repo.find_accounts.return_value = (["some-account"], 1)
    translator.translate.return_value = TransactionFilter(
        type="WITHDRAWAL", from_date=date(2026, 7, 1), to_date=date(2026, 7, 31)
    )
    repo.find_transactions.return_value = ([], 0)
    composer.compose.return_value = "No matching transactions were found."
    handler = AskTransactionHistoryHandler(repo, translator, composer)

    result = await handler.execute(
        AskTransactionHistoryQuery(
            account_id="account-1", requester_id="owner-1", question="How much did I withdraw in July?"
        )
    )

    repo.find_accounts.assert_awaited_once_with(page=0, take=1, account_id="account-1", owner_id="owner-1")
    repo.find_transactions.assert_awaited_once_with(
        "account-1", page=0, take=50, type="WITHDRAWAL", from_date=date(2026, 7, 1), to_date=date(2026, 7, 31)
    )
    assert result.matched_count == 0


@pytest.mark.asyncio
async def test_execute_composes_the_answer_from_the_retrieved_transactions_and_returns_the_match_count(
    repo, translator, composer
) -> None:
    repo.find_accounts.return_value = (["some-account"], 1)
    translator.translate.return_value = TransactionFilter()
    transaction = TransactionSummary(
        transaction_id="t1",
        type="DEPOSIT",
        amount=MoneyResult(amount=1000, currency="KRW"),
        created_at=datetime(2026, 7, 1, 12, 0, 0),
    )
    repo.find_transactions.return_value = ([transaction], 1)
    composer.compose.return_value = "You deposited 1000 KRW."
    handler = AskTransactionHistoryHandler(repo, translator, composer)

    result = await handler.execute(
        AskTransactionHistoryQuery(account_id="account-1", requester_id="owner-1", question="How much did I deposit?")
    )

    assert result.answer == "You deposited 1000 KRW."
    assert result.matched_count == 1
    composer.compose.assert_awaited_once_with("How much did I deposit?", [transaction])


@pytest.mark.asyncio
async def test_execute_raises_AccountNotFoundError_and_never_calls_translator_or_composer_when_account_is_missing(
    repo, translator, composer
) -> None:
    repo.find_accounts.return_value = ([], 0)
    handler = AskTransactionHistoryHandler(repo, translator, composer)

    with pytest.raises(AccountNotFoundError):
        await handler.execute(
            AskTransactionHistoryQuery(account_id="account-1", requester_id="owner-1", question="anything")
        )

    translator.translate.assert_not_awaited()
    composer.compose.assert_not_awaited()
    repo.find_transactions.assert_not_awaited()
