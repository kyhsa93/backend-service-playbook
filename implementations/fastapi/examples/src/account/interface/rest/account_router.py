from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from ....auth.interface.rest.dependencies import CurrentUser, get_current_user
from ....common.rate_limit import limiter, rate_limit_config
from ....outbox.outbox_relay import OutboxRelay
from ...application.command.close_account_handler import CloseAccountCommand, CloseAccountHandler
from ...application.command.create_account_handler import CreateAccountCommand, CreateAccountHandler
from ...application.command.deposit_handler import DepositCommand, DepositHandler
from ...application.command.reactivate_account_handler import ReactivateAccountCommand, ReactivateAccountHandler
from ...application.command.suspend_account_handler import SuspendAccountCommand, SuspendAccountHandler
from ...application.command.withdraw_handler import WithdrawCommand, WithdrawHandler
from ...application.event.account_closed_event_handler import AccountClosedEventHandler
from ...application.event.account_created_event_handler import AccountCreatedEventHandler
from ...application.event.account_reactivated_event_handler import AccountReactivatedEventHandler
from ...application.event.account_suspended_event_handler import AccountSuspendedEventHandler
from ...application.event.money_deposited_event_handler import MoneyDepositedEventHandler
from ...application.event.money_withdrawn_event_handler import MoneyWithdrawnEventHandler
from ...application.query.get_account_handler import GetAccountHandler, GetAccountQuery
from ...application.query.get_transactions_handler import GetTransactionsHandler, GetTransactionsQuery
from ...application.service.notification_service import NotificationService
from ....database import get_session
from ...infrastructure.notification.notification_service import SesNotificationService
from ...infrastructure.persistence.account_repository import SqlAlchemyAccountRepository
from .schemas import (
    CreateAccountRequest,
    CreateAccountResponse,
    DepositRequest,
    GetAccountResponse,
    GetTransactionsResponse,
    TransactionResponse,
    WithdrawRequest,
)

router = APIRouter(prefix="/accounts", tags=["Account"], dependencies=[Depends(get_current_user)])


def _repo(session: AsyncSession = Depends(get_session)) -> SqlAlchemyAccountRepository:
    return SqlAlchemyAccountRepository(session)


def _notification_service(session: AsyncSession = Depends(get_session)) -> NotificationService:
    return SesNotificationService(session)


def _outbox_relay(
    session: AsyncSession = Depends(get_session),
    notification_service: NotificationService = Depends(_notification_service),
) -> OutboxRelay:
    return OutboxRelay(
        session=session,
        handlers={
            "AccountCreated": AccountCreatedEventHandler(notification_service).handle,
            "MoneyDeposited": MoneyDepositedEventHandler(notification_service).handle,
            "MoneyWithdrawn": MoneyWithdrawnEventHandler(notification_service).handle,
            "AccountSuspended": AccountSuspendedEventHandler(notification_service).handle,
            "AccountReactivated": AccountReactivatedEventHandler(notification_service).handle,
            "AccountClosed": AccountClosedEventHandler(notification_service).handle,
        },
    )


@router.post("", status_code=201, response_model=CreateAccountResponse)
@limiter.limit(rate_limit_config.write_limit)
async def create_account(
    request: Request,
    body: CreateAccountRequest,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
    outbox_relay: OutboxRelay = Depends(_outbox_relay),
) -> CreateAccountResponse:
    account = await CreateAccountHandler(repo, outbox_relay).execute(
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
    outbox_relay: OutboxRelay = Depends(_outbox_relay),
) -> TransactionResponse:
    transaction = await DepositHandler(repo, outbox_relay).execute(
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
    outbox_relay: OutboxRelay = Depends(_outbox_relay),
) -> TransactionResponse:
    transaction = await WithdrawHandler(repo, outbox_relay).execute(
        WithdrawCommand(account_id=account_id, requester_id=current_user.user_id, amount=body.amount)
    )
    return TransactionResponse(
        transaction_id=transaction.transaction_id,
        account_id=transaction.account_id,
        type=transaction.type,
        amount={"amount": transaction.amount.amount, "currency": transaction.amount.currency},
        created_at=transaction.created_at,
    )


@router.post("/{account_id}/suspend", status_code=204)
@limiter.limit(rate_limit_config.write_limit)
async def suspend_account(
    request: Request,
    account_id: str,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
    outbox_relay: OutboxRelay = Depends(_outbox_relay),
) -> None:
    await SuspendAccountHandler(repo, outbox_relay).execute(
        SuspendAccountCommand(account_id=account_id, requester_id=current_user.user_id)
    )


@router.post("/{account_id}/reactivate", status_code=204)
@limiter.limit(rate_limit_config.write_limit)
async def reactivate_account(
    request: Request,
    account_id: str,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
    outbox_relay: OutboxRelay = Depends(_outbox_relay),
) -> None:
    await ReactivateAccountHandler(repo, outbox_relay).execute(
        ReactivateAccountCommand(account_id=account_id, requester_id=current_user.user_id)
    )


@router.post("/{account_id}/close", status_code=204)
@limiter.limit(rate_limit_config.write_limit)
async def close_account(
    request: Request,
    account_id: str,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
    outbox_relay: OutboxRelay = Depends(_outbox_relay),
) -> None:
    await CloseAccountHandler(repo, outbox_relay).execute(
        CloseAccountCommand(account_id=account_id, requester_id=current_user.user_id)
    )


@router.get("/{account_id}", response_model=GetAccountResponse)
async def get_account(
    account_id: str,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
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
    repo: SqlAlchemyAccountRepository = Depends(_repo),
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
