from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from ....auth.interface.rest.dependencies import CurrentUser, get_current_user
from ....common.rate_limit import limiter, rate_limit_config
from ....database import get_session
from ...application.command.close_account_handler import CloseAccountCommand, CloseAccountHandler
from ...application.command.create_account_handler import CreateAccountCommand, CreateAccountHandler
from ...application.command.deposit_handler import DepositCommand, DepositHandler
from ...application.command.reactivate_account_handler import ReactivateAccountCommand, ReactivateAccountHandler
from ...application.command.suspend_account_handler import SuspendAccountCommand, SuspendAccountHandler
from ...application.command.transfer_handler import TransferCommand, TransferHandler
from ...application.command.withdraw_handler import WithdrawCommand, WithdrawHandler
from ...application.query.get_account_handler import GetAccountHandler, GetAccountQuery
from ...application.query.get_transactions_handler import GetTransactionsHandler, GetTransactionsQuery
from ...domain.repository import AccountQuery
from ...infrastructure.persistence.account_repository import SqlAlchemyAccountRepository
from .schemas import (
    CreateAccountRequest,
    CreateAccountResponse,
    DepositRequest,
    GetAccountResponse,
    GetTransactionsResponse,
    TransactionResponse,
    TransferRequest,
    TransferResponse,
    WithdrawRequest,
)

# Outbox → SQS 발행/수신은 독립적으로 주기 실행되는
# OutboxPoller/OutboxConsumer(`src/outbox/outbox_poller.py`/`outbox_consumer.py`)만의
# 책임이고, 그 배선(eventType → 핸들러)은 `src/outbox/event_handlers.py`의
# `build_event_handlers()`가 담당한다 — 이 라우터는 Account 자신의 라우트에만
# 집중한다(domain-events.md 참고).

router = APIRouter(prefix="/accounts", tags=["Account"], dependencies=[Depends(get_current_user)])


def _repo(session: AsyncSession = Depends(get_session)) -> SqlAlchemyAccountRepository:
    return SqlAlchemyAccountRepository(session)


def _query_repo(session: AsyncSession = Depends(get_session)) -> AccountQuery:
    return SqlAlchemyAccountRepository(session)


@router.post("", status_code=201, response_model=CreateAccountResponse)
@limiter.limit(rate_limit_config.write_limit)
async def create_account(
    request: Request,
    body: CreateAccountRequest,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
) -> CreateAccountResponse:
    account = await CreateAccountHandler(repo).execute(
        CreateAccountCommand(requester_id=current_user.user_id, currency=body.currency, email=body.email)
    )
    return CreateAccountResponse(
        account_id=account.account_id,
        owner_id=account.owner_id,
        email=account.email,
        balance={"amount": account.balance.amount, "currency": account.balance.currency},
        status=account.status.value,
        created_at=account.created_at,
    )


@router.post("/{account_id}/deposit", status_code=201, response_model=TransactionResponse)
@limiter.limit(rate_limit_config.write_limit)
async def deposit(
    request: Request,
    account_id: str,
    body: DepositRequest,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
) -> TransactionResponse:
    transaction = await DepositHandler(repo).execute(
        DepositCommand(account_id=account_id, requester_id=current_user.user_id, amount=body.amount)
    )
    return TransactionResponse(
        transaction_id=transaction.transaction_id,
        account_id=transaction.account_id,
        type=transaction.type,
        amount={"amount": transaction.amount.amount, "currency": transaction.amount.currency},
        created_at=transaction.created_at,
    )


@router.post("/{account_id}/withdraw", status_code=201, response_model=TransactionResponse)
@limiter.limit(rate_limit_config.write_limit)
async def withdraw(
    request: Request,
    account_id: str,
    body: WithdrawRequest,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
) -> TransactionResponse:
    transaction = await WithdrawHandler(repo).execute(
        WithdrawCommand(account_id=account_id, requester_id=current_user.user_id, amount=body.amount)
    )
    return TransactionResponse(
        transaction_id=transaction.transaction_id,
        account_id=transaction.account_id,
        type=transaction.type,
        amount={"amount": transaction.amount.amount, "currency": transaction.amount.currency},
        created_at=transaction.created_at,
    )


@router.post("/{account_id}/transfer", status_code=201, response_model=TransferResponse)
@limiter.limit(rate_limit_config.write_limit)
async def transfer(
    request: Request,
    account_id: str,
    body: TransferRequest,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
) -> TransferResponse:
    result = await TransferHandler(repo).execute(
        TransferCommand(
            source_account_id=account_id,
            target_account_id=body.target_account_id,
            requester_id=current_user.user_id,
            amount=body.amount,
        )
    )
    return TransferResponse(
        transfer_id=result.transfer_id,
        source_transaction=TransactionResponse(
            transaction_id=result.source_transaction.transaction_id,
            account_id=result.source_transaction.account_id,
            type=result.source_transaction.type,
            amount={
                "amount": result.source_transaction.amount.amount,
                "currency": result.source_transaction.amount.currency,
            },
            created_at=result.source_transaction.created_at,
        ),
        target_transaction=TransactionResponse(
            transaction_id=result.target_transaction.transaction_id,
            account_id=result.target_transaction.account_id,
            type=result.target_transaction.type,
            amount={
                "amount": result.target_transaction.amount.amount,
                "currency": result.target_transaction.amount.currency,
            },
            created_at=result.target_transaction.created_at,
        ),
    )


@router.post("/{account_id}/suspend", status_code=204)
@limiter.limit(rate_limit_config.write_limit)
async def suspend_account(
    request: Request,
    account_id: str,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
) -> None:
    await SuspendAccountHandler(repo).execute(
        SuspendAccountCommand(account_id=account_id, requester_id=current_user.user_id)
    )


@router.post("/{account_id}/reactivate", status_code=204)
@limiter.limit(rate_limit_config.write_limit)
async def reactivate_account(
    request: Request,
    account_id: str,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
) -> None:
    await ReactivateAccountHandler(repo).execute(
        ReactivateAccountCommand(account_id=account_id, requester_id=current_user.user_id)
    )


@router.post("/{account_id}/close", status_code=204)
@limiter.limit(rate_limit_config.write_limit)
async def close_account(
    request: Request,
    account_id: str,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
) -> None:
    await CloseAccountHandler(repo).execute(
        CloseAccountCommand(account_id=account_id, requester_id=current_user.user_id)
    )


@router.get("/{account_id}", response_model=GetAccountResponse)
async def get_account(
    account_id: str,
    current_user: CurrentUser = Depends(get_current_user),
    repo: AccountQuery = Depends(_query_repo),
) -> GetAccountResponse:
    result = await GetAccountHandler(repo).execute(
        GetAccountQuery(account_id=account_id, requester_id=current_user.user_id)
    )
    return GetAccountResponse(
        account_id=result.account_id,
        owner_id=result.owner_id,
        email=result.email,
        balance={"amount": result.balance.amount, "currency": result.balance.currency},
        status=result.status,
        created_at=result.created_at,
        updated_at=result.updated_at,
    )


@router.get("/{account_id}/transactions", response_model=GetTransactionsResponse)
async def get_transactions(
    account_id: str,
    current_user: CurrentUser = Depends(get_current_user),
    page: int = 0,
    take: int = 20,
    repo: AccountQuery = Depends(_query_repo),
) -> GetTransactionsResponse:
    result = await GetTransactionsHandler(repo).execute(
        GetTransactionsQuery(account_id=account_id, requester_id=current_user.user_id, page=page, take=take)
    )
    return GetTransactionsResponse(
        transactions=[
            {
                "transaction_id": t.transaction_id,
                "type": t.type,
                "amount": {"amount": t.amount.amount, "currency": t.amount.currency},
                "created_at": t.created_at,
            }
            for t in result.transactions
        ],
        count=result.count,
    )
