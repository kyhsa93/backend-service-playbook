from datetime import datetime
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest

from src.account.application.query.result import MoneyResult, TransactionSummary
from src.account.infrastructure.nl_transaction_answer_composer_impl import NlTransactionAnswerComposerImpl

MODULE = "src.account.infrastructure.nl_transaction_answer_composer_impl"


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
    response.json.return_value = {"message": {"content": content}} if content is not None else {"message": {}}
    return response


def _sample_transactions() -> list[TransactionSummary]:
    return [
        TransactionSummary(
            transaction_id="t1",
            type="DEPOSIT",
            amount=MoneyResult(amount=1000, currency="KRW"),
            created_at=datetime(2026, 7, 1, 12, 0, 0),
        )
    ]


@pytest.mark.asyncio
async def test_compose_when_the_model_returns_content_then_returns_it_stripped() -> None:
    response = _ollama_response(200, "  You deposited 1000 KRW.  ")
    with patch(f"{MODULE}.httpx.AsyncClient", return_value=_client_returning(response)):
        answer = await NlTransactionAnswerComposerImpl().compose("How much did I deposit?", _sample_transactions())

    assert answer == "You deposited 1000 KRW."


@pytest.mark.asyncio
async def test_compose_when_the_model_returns_blank_content_then_falls_back_to_a_templated_summary() -> None:
    response = _ollama_response(200, "   ")
    with patch(f"{MODULE}.httpx.AsyncClient", return_value=_client_returning(response)):
        answer = await NlTransactionAnswerComposerImpl().compose("How much did I deposit?", _sample_transactions())

    assert answer == "Found 1 matching transaction(s):\n- DEPOSIT 1000 KRW on 2026-07-01"


@pytest.mark.asyncio
async def test_compose_with_no_matching_transactions_falls_back_to_a_plain_no_match_message() -> None:
    response = _ollama_response(200, "")
    with patch(f"{MODULE}.httpx.AsyncClient", return_value=_client_returning(response)):
        answer = await NlTransactionAnswerComposerImpl().compose("Anything unusual?", [])

    assert answer == "No matching transactions were found."


@pytest.mark.asyncio
async def test_compose_when_ollama_responds_with_a_non_ok_status_then_falls_back_to_a_templated_summary() -> None:
    response = _ollama_response(500, None)
    with patch(f"{MODULE}.httpx.AsyncClient", return_value=_client_returning(response)):
        answer = await NlTransactionAnswerComposerImpl().compose("How much did I deposit?", _sample_transactions())

    assert answer == "Found 1 matching transaction(s):\n- DEPOSIT 1000 KRW on 2026-07-01"


@pytest.mark.asyncio
async def test_compose_when_the_ollama_call_fails_then_falls_back_rather_than_raising() -> None:
    with patch(
        f"{MODULE}.httpx.AsyncClient",
        return_value=_client_returning(side_effect=httpx.ConnectError("connection refused")),
    ):
        answer = await NlTransactionAnswerComposerImpl().compose("How much did I deposit?", _sample_transactions())

    assert answer == "Found 1 matching transaction(s):\n- DEPOSIT 1000 KRW on 2026-07-01"
