from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from ....account.domain.repository import AccountQuery
from ....account.infrastructure.persistence.account_repository import SqlAlchemyAccountRepository
from ....auth.interface.rest.dependencies import CurrentUser, get_current_user
from ....common.rate_limit import limiter, rate_limit_config
from ....database import get_session
from ...application.adapter.account_adapter import AccountAdapter
from ...application.command.issue_card_handler import IssueCardCommand, IssueCardHandler
from ...application.query.get_card_handler import GetCardHandler, GetCardQuery
from ...domain.repository import CardQuery, CardRepository
from ...infrastructure.account_adapter_impl import AccountAdapterImpl
from ...infrastructure.persistence.card_repository import SqlAlchemyCardRepository
from .schemas import GetCardResponse, IssueCardRequest, IssueCardResponse

router = APIRouter(prefix="/cards", tags=["Card"], dependencies=[Depends(get_current_user)])


def _repo(session: AsyncSession = Depends(get_session)) -> CardRepository:
    return SqlAlchemyCardRepository(session)


def _query_repo(session: AsyncSession = Depends(get_session)) -> CardQuery:
    return SqlAlchemyCardRepository(session)


def _account_query(session: AsyncSession = Depends(get_session)) -> AccountQuery:
    return SqlAlchemyAccountRepository(session)


def _account_adapter(account_query: AccountQuery = Depends(_account_query)) -> AccountAdapter:
    return AccountAdapterImpl(account_query)


@router.post("", status_code=201, response_model=IssueCardResponse)
@limiter.limit(rate_limit_config.write_limit)
async def issue_card(
    request: Request,
    body: IssueCardRequest,
    current_user: CurrentUser = Depends(get_current_user),
    repo: CardRepository = Depends(_repo),
    account_adapter: AccountAdapter = Depends(_account_adapter),
) -> IssueCardResponse:
    card = await IssueCardHandler(repo, account_adapter).execute(
        IssueCardCommand(requester_id=current_user.user_id, account_id=body.account_id, brand=body.brand)
    )
    return IssueCardResponse(
        card_id=card.card_id,
        account_id=card.account_id,
        owner_id=card.owner_id,
        brand=card.brand,
        status=card.status.value,
        created_at=card.created_at,
    )


@router.get("/{card_id}", response_model=GetCardResponse)
async def get_card(
    card_id: str,
    current_user: CurrentUser = Depends(get_current_user),
    repo: CardQuery = Depends(_query_repo),
) -> GetCardResponse:
    result = await GetCardHandler(repo).execute(GetCardQuery(card_id=card_id, requester_id=current_user.user_id))
    return GetCardResponse(
        card_id=result.card_id,
        account_id=result.account_id,
        owner_id=result.owner_id,
        brand=result.brand,
        status=result.status,
        created_at=result.created_at,
    )
