from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest

from src.account.infrastructure.transaction_auto_categorizer_impl import TransactionAutoCategorizerImpl

MODULE = "src.account.infrastructure.transaction_auto_categorizer_impl"


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


@pytest.mark.asyncio
async def test_categorize_when_the_model_returns_a_valid_category_then_returns_it() -> None:
    response = _ollama_response(200, '{"category": "FOOD"}')
    with patch(f"{MODULE}.httpx.AsyncClient", return_value=_client_returning(response)):
        category = await TransactionAutoCategorizerImpl().categorize("Starbucks Gangnam", 5500)

    assert category == "FOOD"


@pytest.mark.asyncio
async def test_categorize_when_the_model_returns_an_out_of_taxonomy_answer_then_falls_back_to_other() -> None:
    response = _ollama_response(200, '{"category": "NOT_A_REAL_CATEGORY"}')
    with patch(f"{MODULE}.httpx.AsyncClient", return_value=_client_returning(response)):
        category = await TransactionAutoCategorizerImpl().categorize("Some Merchant", 1000)

    assert category == "OTHER"


@pytest.mark.asyncio
async def test_categorize_when_ollama_responds_with_a_non_ok_status_then_falls_back_to_other() -> None:
    response = _ollama_response(500, None)
    with patch(f"{MODULE}.httpx.AsyncClient", return_value=_client_returning(response)):
        category = await TransactionAutoCategorizerImpl().categorize("Some Merchant", 1000)

    assert category == "OTHER"


@pytest.mark.asyncio
async def test_categorize_when_the_ollama_call_fails_then_falls_back_to_other_rather_than_raising() -> None:
    with patch(
        f"{MODULE}.httpx.AsyncClient",
        return_value=_client_returning(side_effect=httpx.ConnectError("connection refused")),
    ):
        category = await TransactionAutoCategorizerImpl().categorize("Some Merchant", 1000)

    assert category == "OTHER"


@pytest.mark.asyncio
async def test_categorize_when_the_response_body_is_missing_content_then_falls_back_to_other() -> None:
    response = _ollama_response(200, None)
    with patch(f"{MODULE}.httpx.AsyncClient", return_value=_client_returning(response)):
        category = await TransactionAutoCategorizerImpl().categorize("Some Merchant", 1000)

    assert category == "OTHER"
