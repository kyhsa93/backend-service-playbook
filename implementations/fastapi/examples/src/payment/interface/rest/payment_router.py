from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from ....account.domain.repository import AccountQuery
from ....account.infrastructure.persistence.account_repository import SqlAlchemyAccountRepository
from ....auth.interface.rest.dependencies import CurrentUser, get_current_user
from ....card.domain.repository import CardQuery
from ....card.infrastructure.persistence.card_repository import SqlAlchemyCardRepository
from ....common.rate_limit import limiter, rate_limit_config
from ....database import get_session
from ...application.adapter.account_adapter import AccountAdapter
from ...application.adapter.card_adapter import CardAdapter
from ...application.command.cancel_payment_handler import CancelPaymentCommand, CancelPaymentHandler
from ...application.command.create_payment_handler import CreatePaymentCommand, CreatePaymentHandler
from ...application.command.request_refund_handler import RequestRefundCommand, RequestRefundHandler
from ...application.query.get_payment_handler import GetPaymentHandler, GetPaymentQuery
from ...application.query.get_payments_handler import GetPaymentsHandler, GetPaymentsQuery
from ...application.query.get_refunds_handler import GetRefundsHandler, GetRefundsQuery
from ...domain.payment_repository import PaymentQuery, PaymentRepository
from ...domain.refund_repository import RefundQuery, RefundRepository
from ...infrastructure.account_adapter_impl import AccountAdapterImpl
from ...infrastructure.card_adapter_impl import CardAdapterImpl
from ...infrastructure.persistence.payment_repository import SqlAlchemyPaymentRepository
from ...infrastructure.persistence.refund_repository import SqlAlchemyRefundRepository
from .schemas import (
    CancelPaymentRequest,
    CreatePaymentRequest,
    GetPaymentsResponse,
    GetRefundsResponse,
    PaymentResponse,
    RefundResponse,
    RequestRefundRequest,
)

router = APIRouter(prefix="/payments", tags=["Payment"], dependencies=[Depends(get_current_user)])


def _repo(session: AsyncSession = Depends(get_session)) -> PaymentRepository:
    return SqlAlchemyPaymentRepository(session)


def _query_repo(session: AsyncSession = Depends(get_session)) -> PaymentQuery:
    return SqlAlchemyPaymentRepository(session)


def _refund_repo(session: AsyncSession = Depends(get_session)) -> RefundRepository:
    return SqlAlchemyRefundRepository(session)


def _refund_query_repo(session: AsyncSession = Depends(get_session)) -> RefundQuery:
    return SqlAlchemyRefundRepository(session)


def _card_query(session: AsyncSession = Depends(get_session)) -> CardQuery:
    return SqlAlchemyCardRepository(session)


def _account_query(session: AsyncSession = Depends(get_session)) -> AccountQuery:
    return SqlAlchemyAccountRepository(session)


def _card_adapter(card_query: CardQuery = Depends(_card_query)) -> CardAdapter:
    return CardAdapterImpl(card_query)


def _account_adapter(account_query: AccountQuery = Depends(_account_query)) -> AccountAdapter:
    return AccountAdapterImpl(account_query)


@router.post("", status_code=201, response_model=PaymentResponse)
@limiter.limit(rate_limit_config.write_limit)
async def create_payment(
    request: Request,
    body: CreatePaymentRequest,
    current_user: CurrentUser = Depends(get_current_user),
    repo: PaymentRepository = Depends(_repo),
    card_adapter: CardAdapter = Depends(_card_adapter),
    account_adapter: AccountAdapter = Depends(_account_adapter),
) -> PaymentResponse:
    payment = await CreatePaymentHandler(repo, card_adapter, account_adapter).execute(
        CreatePaymentCommand(requester_id=current_user.user_id, card_id=body.card_id, amount=body.amount)
    )
    return PaymentResponse(
        payment_id=payment.payment_id,
        card_id=payment.card_id,
        account_id=payment.account_id,
        owner_id=payment.owner_id,
        amount=payment.amount,
        status=payment.status.value,
        created_at=payment.created_at,
    )


@router.post("/{payment_id}/cancel", status_code=204)
@limiter.limit(rate_limit_config.write_limit)
async def cancel_payment(
    request: Request,
    payment_id: str,
    body: CancelPaymentRequest,
    current_user: CurrentUser = Depends(get_current_user),
    repo: PaymentRepository = Depends(_repo),
) -> None:
    await CancelPaymentHandler(repo).execute(
        CancelPaymentCommand(requester_id=current_user.user_id, payment_id=payment_id, reason=body.reason)
    )


@router.get("/{payment_id}", response_model=PaymentResponse)
async def get_payment(
    payment_id: str,
    current_user: CurrentUser = Depends(get_current_user),
    query: PaymentQuery = Depends(_query_repo),
) -> PaymentResponse:
    result = await GetPaymentHandler(query).execute(
        GetPaymentQuery(payment_id=payment_id, requester_id=current_user.user_id)
    )
    return PaymentResponse(
        payment_id=result.payment_id,
        card_id=result.card_id,
        account_id=result.account_id,
        owner_id=result.owner_id,
        amount=result.amount,
        status=result.status,
        created_at=result.created_at,
    )


@router.get("", response_model=GetPaymentsResponse)
async def get_payments(
    current_user: CurrentUser = Depends(get_current_user),
    page: int = 0,
    take: int = 20,
    query: PaymentQuery = Depends(_query_repo),
) -> GetPaymentsResponse:
    # Looks up only the authenticated requester's own payment history — a client-specified
    # ownerId query parameter is never accepted (no endpoint in this repository does that).
    result = await GetPaymentsHandler(query).execute(
        GetPaymentsQuery(requester_id=current_user.user_id, page=page, take=take)
    )
    return GetPaymentsResponse(
        payments=[
            PaymentResponse(
                payment_id=p.payment_id,
                card_id=p.card_id,
                account_id=p.account_id,
                owner_id=p.owner_id,
                amount=p.amount,
                status=p.status,
                created_at=p.created_at,
            )
            for p in result.payments
        ],
        count=result.count,
    )


@router.post("/{payment_id}/refunds", status_code=201, response_model=RefundResponse)
@limiter.limit(rate_limit_config.write_limit)
async def request_refund(
    request: Request,
    payment_id: str,
    body: RequestRefundRequest,
    current_user: CurrentUser = Depends(get_current_user),
    payment_repo: PaymentRepository = Depends(_repo),
    refund_repo: RefundRepository = Depends(_refund_repo),
) -> RefundResponse:
    # A refund rejection is a valid state transition from a domain point of view —
    # RequestRefundHandler never throws an exception even on rejection, returning a Refund
    # with REJECTED status instead, so this endpoint responds with 201 + a status field for
    # both approval and rejection (never a 4xx).
    refund = await RequestRefundHandler(payment_repo, refund_repo).execute(
        RequestRefundCommand(
            requester_id=current_user.user_id, payment_id=payment_id, amount=body.amount, reason=body.reason
        )
    )
    return RefundResponse(
        refund_id=refund.refund_id,
        payment_id=refund.payment_id,
        amount=refund.amount,
        reason=refund.reason,
        status=refund.status.value,
        decision_note=refund.decision_note,
        created_at=refund.created_at,
    )


@router.get("/{payment_id}/refunds", response_model=GetRefundsResponse)
async def get_refunds(
    payment_id: str,
    current_user: CurrentUser = Depends(get_current_user),
    page: int = 0,
    take: int = 20,
    payment_query: PaymentQuery = Depends(_query_repo),
    refund_query: RefundQuery = Depends(_refund_query_repo),
) -> GetRefundsResponse:
    result = await GetRefundsHandler(payment_query, refund_query).execute(
        GetRefundsQuery(payment_id=payment_id, requester_id=current_user.user_id, page=page, take=take)
    )
    return GetRefundsResponse(
        refunds=[
            RefundResponse(
                refund_id=r.refund_id,
                payment_id=r.payment_id,
                amount=r.amount,
                reason=r.reason,
                status=r.status,
                decision_note=r.decision_note,
                created_at=r.created_at,
            )
            for r in result.refunds
        ],
        count=result.count,
    )
