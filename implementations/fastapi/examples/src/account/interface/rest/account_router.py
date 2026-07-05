from fastapi import APIRouter, Depends, Header
from sqlalchemy.ext.asyncio import AsyncSession

from ...application.command.close_account_handler import CloseAccountCommand, CloseAccountHandler
from ...application.command.create_account_handler import CreateAccountCommand, CreateAccountHandler
from ...application.command.deposit_handler import DepositCommand, DepositHandler
from ...application.command.reactivate_account_handler import ReactivateAccountCommand, ReactivateAccountHandler
from ...application.command.suspend_account_handler import SuspendAccountCommand, SuspendAccountHandler
from ...application.command.withdraw_handler import WithdrawCommand, WithdrawHandler
from ...application.query.get_account_handler import GetAccountHandler, GetAccountQuery
from ...application.query.get_transactions_handler import GetTransactionsHandler, GetTransactionsQuery
from ....database import get_session
from ...infrastructure.notification.notification_service import NotificationService
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

router = APIRouter(prefix="/accounts", tags=["Account"])


def _repo(session: AsyncSession = Depends(get_session)) -> SqlAlchemyAccountRepository:
    return SqlAlchemyAccountRepository(session)


def _notification_service(session: AsyncSession = Depends(get_session)) -> NotificationService:
    return NotificationService(session)


@router.post("", status_code=201, response_model=CreateAccountResponse)
async def create_account(
    body: CreateAccountRequest,
    x_user_id: str = Header(...),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
    notification_service: NotificationService = Depends(_notification_service),
) -> CreateAccountResponse:
    account = await CreateAccountHandler(repo, notification_service).execute(
        CreateAccountCommand(requester_id=x_user_id, currency=body.currency, email=body.email)
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
async def deposit(
    account_id: str,
    body: DepositRequest,
    x_user_id: str = Header(...),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
    notification_service: NotificationService = Depends(_notification_service),
) -> TransactionResponse:
    transaction = await DepositHandler(repo, notification_service).execute(
        DepositCommand(account_id=account_id, requester_id=x_user_id, amount=body.amount)
    )
    return TransactionResponse(
        transaction_id=transaction.transaction_id,
        account_id=transaction.account_id,
        type=transaction.type,
        amount={"amount": transaction.amount.amount, "currency": transaction.amount.currency},
        created_at=transaction.created_at,
    )


@router.post("/{account_id}/withdraw", status_code=201, response_model=TransactionResponse)
async def withdraw(
    account_id: str,
    body: WithdrawRequest,
    x_user_id: str = Header(...),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
    notification_service: NotificationService = Depends(_notification_service),
) -> TransactionResponse:
    transaction = await WithdrawHandler(repo, notification_service).execute(
        WithdrawCommand(account_id=account_id, requester_id=x_user_id, amount=body.amount)
    )
    return TransactionResponse(
        transaction_id=transaction.transaction_id,
        account_id=transaction.account_id,
        type=transaction.type,
        amount={"amount": transaction.amount.amount, "currency": transaction.amount.currency},
        created_at=transaction.created_at,
    )


@router.post("/{account_id}/suspend", status_code=204)
async def suspend_account(
    account_id: str,
    x_user_id: str = Header(...),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
    notification_service: NotificationService = Depends(_notification_service),
) -> None:
    await SuspendAccountHandler(repo, notification_service).execute(
        SuspendAccountCommand(account_id=account_id, requester_id=x_user_id)
    )


@router.post("/{account_id}/reactivate", status_code=204)
async def reactivate_account(
    account_id: str,
    x_user_id: str = Header(...),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
    notification_service: NotificationService = Depends(_notification_service),
) -> None:
    await ReactivateAccountHandler(repo, notification_service).execute(
        ReactivateAccountCommand(account_id=account_id, requester_id=x_user_id)
    )


@router.post("/{account_id}/close", status_code=204)
async def close_account(
    account_id: str,
    x_user_id: str = Header(...),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
    notification_service: NotificationService = Depends(_notification_service),
) -> None:
    await CloseAccountHandler(repo, notification_service).execute(
        CloseAccountCommand(account_id=account_id, requester_id=x_user_id)
    )


@router.get("/{account_id}", response_model=GetAccountResponse)
async def get_account(
    account_id: str,
    x_user_id: str = Header(...),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
) -> GetAccountResponse:
    result = await GetAccountHandler(repo).execute(GetAccountQuery(account_id=account_id, requester_id=x_user_id))
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
    x_user_id: str = Header(...),
    page: int = 0,
    take: int = 20,
    repo: SqlAlchemyAccountRepository = Depends(_repo),
) -> GetTransactionsResponse:
    result = await GetTransactionsHandler(repo).execute(
        GetTransactionsQuery(account_id=account_id, requester_id=x_user_id, page=page, take=take)
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
