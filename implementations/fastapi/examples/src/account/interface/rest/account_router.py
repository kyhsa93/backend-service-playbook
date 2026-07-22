from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from ....auth.interface.rest.dependencies import CurrentUser, get_current_user
from ....common.error_response import ErrorResponse
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

# Publishing/receiving Outbox → SQS is the sole responsibility of the independently,
# periodically running OutboxPoller/OutboxConsumer
# (`src/outbox/outbox_poller.py`/`outbox_consumer.py`), and their wiring (eventType →
# handler) is handled by `build_event_handlers()` in `src/outbox/event_handlers.py` — this
# router focuses only on Account's own routes (see domain-events.md).

# `responses={401: ...}` applies to every route on this router (FastAPI merges a router-level
# `responses` dict into each operation's own `responses=`) — every route below requires
# `get_current_user`, so 401 is a possible response everywhere without repeating it per-route
# (mirrors nestjs's class-level `@ApiUnauthorizedResponse`, see api-response.md).
router = APIRouter(
    prefix="/accounts",
    tags=["Account"],
    dependencies=[Depends(get_current_user)],
    responses={
        401: {
            "model": ErrorResponse,
            "description": "The bearer token is missing, malformed, or invalid (`INVALID_TOKEN`).",
        }
    },
)


def _repo(session: AsyncSession = Depends(get_session)) -> SqlAlchemyAccountRepository:
    return SqlAlchemyAccountRepository(session)


def _query_repo(session: AsyncSession = Depends(get_session)) -> AccountQuery:
    return SqlAlchemyAccountRepository(session)


@router.post(
    "",
    status_code=201,
    response_model=CreateAccountResponse,
    summary="Open a new account",
    description="Opens a new account for the authenticated requester with a 0 balance in the given currency.",
    responses={
        422: {
            "model": ErrorResponse,
            "description": (
                "Request validation failed (`VALIDATION_FAILED`) — e.g. an invalid email or missing currency."
            ),
        }
    },
)
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


@router.post(
    "/{account_id}/deposit",
    status_code=201,
    response_model=TransactionResponse,
    summary="Deposit money into an account",
    description="Credits the given amount to the account and records a `DEPOSIT` transaction.",
    responses={
        400: {
            "model": ErrorResponse,
            "description": (
                "One of: the amount is not a positive integer (`INVALID_AMOUNT`), or the account is not "
                "active (`DEPOSIT_REQUIRES_ACTIVE_ACCOUNT`)."
            ),
        },
        404: {
            "model": ErrorResponse,
            "description": "No account exists with the given `account_id` for this requester (`ACCOUNT_NOT_FOUND`).",
        },
        422: {"model": ErrorResponse, "description": "Request validation failed (`VALIDATION_FAILED`)."},
    },
)
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


@router.post(
    "/{account_id}/withdraw",
    status_code=201,
    response_model=TransactionResponse,
    summary="Withdraw money from an account",
    description="Debits the given amount from the account and records a `WITHDRAWAL` transaction.",
    responses={
        400: {
            "model": ErrorResponse,
            "description": (
                "One of: the amount is not a positive integer (`INVALID_AMOUNT`), the account is not active "
                "(`WITHDRAW_REQUIRES_ACTIVE_ACCOUNT`), or the balance is insufficient (`INSUFFICIENT_BALANCE`)."
            ),
        },
        404: {
            "model": ErrorResponse,
            "description": "No account exists with the given `account_id` for this requester (`ACCOUNT_NOT_FOUND`).",
        },
        422: {"model": ErrorResponse, "description": "Request validation failed (`VALIDATION_FAILED`)."},
    },
)
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


@router.post(
    "/{account_id}/transfer",
    status_code=201,
    response_model=TransferResponse,
    summary="Transfer money to another account",
    description=(
        "Atomically debits the source account and credits the target account with the given amount, "
        "recording one `WITHDRAWAL` and one `DEPOSIT` transaction."
    ),
    responses={
        400: {
            "model": ErrorResponse,
            "description": (
                "One of: the amount is not a positive integer (`INVALID_AMOUNT`), the source and target "
                "accounts are the same (`TRANSFER_SAME_ACCOUNT`), either account is not active "
                "(`WITHDRAW_REQUIRES_ACTIVE_ACCOUNT`/`DEPOSIT_REQUIRES_ACTIVE_ACCOUNT`), the currencies do "
                "not match (`CURRENCY_MISMATCH`), or the balance is insufficient (`INSUFFICIENT_BALANCE`)."
            ),
        },
        404: {
            "model": ErrorResponse,
            "description": "No account exists with the given source or target `account_id` (`ACCOUNT_NOT_FOUND`).",
        },
        422: {"model": ErrorResponse, "description": "Request validation failed (`VALIDATION_FAILED`)."},
    },
)
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


@router.post(
    "/{account_id}/suspend",
    status_code=204,
    summary="Suspend an account",
    description=(
        "Suspends an active account, blocking further deposits/withdrawals/transfers until it is reactivated."
    ),
    responses={
        400: {
            "model": ErrorResponse,
            "description": "Only an active account can be suspended (`SUSPEND_REQUIRES_ACTIVE_ACCOUNT`).",
        },
        404: {
            "model": ErrorResponse,
            "description": "No account exists with the given `account_id` for this requester (`ACCOUNT_NOT_FOUND`).",
        },
    },
)
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


@router.post(
    "/{account_id}/reactivate",
    status_code=204,
    summary="Reactivate a suspended account",
    description=(
        "Moves a suspended account back to active, restoring its ability to accept deposits/withdrawals/transfers."
    ),
    responses={
        400: {
            "model": ErrorResponse,
            "description": ("Only a suspended account can be reactivated (`REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT`)."),
        },
        404: {
            "model": ErrorResponse,
            "description": "No account exists with the given `account_id` for this requester (`ACCOUNT_NOT_FOUND`).",
        },
    },
)
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


@router.post(
    "/{account_id}/close",
    status_code=204,
    summary="Close an account",
    description=(
        "Permanently closes an account. The balance must be exactly 0 first (withdraw or transfer out "
        "any remaining funds)."
    ),
    responses={
        400: {
            "model": ErrorResponse,
            "description": (
                "One of: the account is already closed (`ACCOUNT_ALREADY_CLOSED`), or the balance is not 0 "
                "(`ACCOUNT_BALANCE_NOT_ZERO`)."
            ),
        },
        404: {
            "model": ErrorResponse,
            "description": "No account exists with the given `account_id` for this requester (`ACCOUNT_NOT_FOUND`).",
        },
    },
)
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


@router.get(
    "/{account_id}",
    response_model=GetAccountResponse,
    summary="Look up an account",
    description="Returns the account only if it belongs to the authenticated requester.",
    responses={
        404: {
            "model": ErrorResponse,
            "description": "No account exists with the given `account_id` for this requester (`ACCOUNT_NOT_FOUND`).",
        }
    },
)
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


@router.get(
    "/{account_id}/transactions",
    response_model=GetTransactionsResponse,
    summary="List an account's transaction history",
    description=(
        "Returns the account's deposit/withdrawal/interest transactions, newest first, paginated with `page`/`take`."
    ),
    responses={
        404: {
            "model": ErrorResponse,
            "description": "No account exists with the given `account_id` for this requester (`ACCOUNT_NOT_FOUND`).",
        },
        422: {
            "model": ErrorResponse,
            "description": "Request validation failed (`VALIDATION_FAILED`) — e.g. a non-integer `page`/`take`.",
        },
    },
)
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
