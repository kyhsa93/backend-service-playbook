from unittest.mock import AsyncMock

import pytest

from src.card.application.query.get_card_handler import GetCardHandler, GetCardQuery
from src.card.domain.card import Card
from src.card.domain.errors import CardNotFoundError


@pytest.fixture
def query_repo() -> AsyncMock:
    return AsyncMock()


@pytest.mark.asyncio
async def test_execute_카드가_있으면_결과를_반환한다(query_repo) -> None:
    card = Card.issue(account_id="account-1", owner_id="owner-1", brand="VISA")
    query_repo.find_cards.return_value = ([card], 1)
    handler = GetCardHandler(query_repo)

    result = await handler.execute(GetCardQuery(card_id=card.card_id, requester_id="owner-1"))

    assert result.card_id == card.card_id
    assert result.account_id == "account-1"
    assert result.status == "ACTIVE"
    query_repo.find_cards.assert_awaited_once_with(page=0, take=1, card_id=card.card_id, owner_id="owner-1")


@pytest.mark.asyncio
async def test_execute_카드가_없으면_CardNotFoundError(query_repo) -> None:
    query_repo.find_cards.return_value = ([], 0)
    handler = GetCardHandler(query_repo)

    with pytest.raises(CardNotFoundError):
        await handler.execute(GetCardQuery(card_id="non-existent", requester_id="owner-1"))
