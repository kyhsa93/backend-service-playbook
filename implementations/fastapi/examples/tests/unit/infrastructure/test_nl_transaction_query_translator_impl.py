from datetime import date
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest

from src.account.application.service.nl_transaction_query_translator import TransactionFilter
from src.account.infrastructure.nl_transaction_query_translator_impl import NlTransactionQueryTranslatorImpl

MODULE = "src.account.infrastructure.nl_transaction_query_translator_impl"


def _client_returning(response: MagicMock | None = None, side_effect: Exception | None = None) -> AsyncMock:
    mock_client = AsyncMock()
    if side_effect is not None:
        mock_client.post.side_effect = side_effect
    else:
        mock_client.post.return_value = response

    mock_client_cm = AsyncMock()
    mock_client_cm.__aenter__.return_value = mock_client
    mock_client_cm.__aexit__.return_value = False
    return mock_client_cm


def _ollama_response(status_code: int, content: str | None) -> MagicMock:
    response = MagicMock()
    response.status_code = status_code
    # A body with no `message.content` (rather than not stubbing .json() at all, which would
    # return a truthy MagicMock and defeat the "falls back" assertion below).
    response.json.return_value = {"message": {"content": content}} if content is not None else {"message": {}}
    return response


@pytest.mark.asyncio
async def test_translate_when_the_model_returns_a_valid_type_and_dates_then_returns_them_as_the_filter() -> None:
    response = _ollama_response(200, '{"type": "WITHDRAWAL", "fromDate": "2026-07-01", "toDate": "2026-07-31"}')
    with patch(f"{MODULE}.httpx.AsyncClient", return_value=_client_returning(response)):
        filter_ = await NlTransactionQueryTranslatorImpl().translate("How much did I withdraw in July?")

    assert filter_ == TransactionFilter(type="WITHDRAWAL", from_date=date(2026, 7, 1), to_date=date(2026, 7, 31))


@pytest.mark.asyncio
async def test_translate_when_the_model_returns_an_invalid_type_then_drops_it_rather_than_passing_it_through() -> None:
    response = _ollama_response(200, '{"type": "NOT_A_REAL_TYPE", "fromDate": "", "toDate": ""}')
    with patch(f"{MODULE}.httpx.AsyncClient", return_value=_client_returning(response)):
        filter_ = await NlTransactionQueryTranslatorImpl().translate("anything")

    assert filter_.type is None


@pytest.mark.asyncio
async def test_translate_when_the_model_returns_a_malformed_date_then_drops_it_rather_than_passing_it_through() -> None:
    response = _ollama_response(200, '{"type": "ANY", "fromDate": "not-a-date", "toDate": "2026-13-99"}')
    with patch(f"{MODULE}.httpx.AsyncClient", return_value=_client_returning(response)):
        filter_ = await NlTransactionQueryTranslatorImpl().translate("anything")

    assert filter_.from_date is None
    assert filter_.to_date is None


@pytest.mark.asyncio
async def test_translate_when_ollama_responds_with_a_non_ok_status_then_falls_back_to_no_filter() -> None:
    response = _ollama_response(500, None)
    with patch(f"{MODULE}.httpx.AsyncClient", return_value=_client_returning(response)):
        filter_ = await NlTransactionQueryTranslatorImpl().translate("anything")

    assert filter_ == TransactionFilter()


@pytest.mark.asyncio
async def test_translate_when_the_ollama_call_fails_then_falls_back_to_no_filter_rather_than_raising() -> None:
    with patch(
        f"{MODULE}.httpx.AsyncClient",
        return_value=_client_returning(side_effect=httpx.ConnectError("connection refused")),
    ):
        filter_ = await NlTransactionQueryTranslatorImpl().translate("anything")

    assert filter_ == TransactionFilter()


@pytest.mark.asyncio
async def test_translate_when_the_response_body_is_missing_content_then_falls_back_to_no_filter() -> None:
    response = _ollama_response(200, None)
    with patch(f"{MODULE}.httpx.AsyncClient", return_value=_client_returning(response)):
        filter_ = await NlTransactionQueryTranslatorImpl().translate("anything")

    assert filter_ == TransactionFilter()
