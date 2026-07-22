from __future__ import annotations

from collections.abc import Awaitable, Callable

from sqlalchemy.ext.asyncio import AsyncSession

from ..account.application.event.account_closed_event_handler import AccountClosedEventHandler
from ..account.application.event.account_created_event_handler import AccountCreatedEventHandler
from ..account.application.event.account_reactivated_event_handler import AccountReactivatedEventHandler
from ..account.application.event.account_suspended_event_handler import AccountSuspendedEventHandler
from ..account.application.event.interest_paid_event_handler import InterestPaidEventHandler
from ..account.application.event.money_deposited_event_handler import MoneyDepositedEventHandler
from ..account.application.event.money_withdrawn_event_handler import MoneyWithdrawnEventHandler
from ..account.domain.repository import AccountRepository
from ..account.infrastructure.notification.notification_service import SesNotificationService
from ..account.infrastructure.persistence.account_repository import SqlAlchemyAccountRepository
from ..account.interface.integration_event.account_integration_event_controller import (
    AccountIntegrationEventController,
)
from ..card.application.event.card_statement_sent_event_handler import CardStatementSentEventHandler
from ..card.domain.repository import CardRepository
from ..card.infrastructure.notification.notification_service import (
    SesNotificationService as CardSesNotificationService,
)
from ..card.infrastructure.persistence.card_repository import SqlAlchemyCardRepository
from ..card.interface.integration_event.card_integration_event_controller import CardIntegrationEventController
from ..payment.application.event.payment_cancelled_event_handler import PaymentCancelledEventHandler
from ..payment.application.event.payment_completed_event_handler import PaymentCompletedEventHandler
from ..payment.application.event.refund_approved_event_handler import RefundApprovedEventHandler
from .outbox_writer import OutboxWriter

EventHandlerFn = Callable[[dict], Awaitable[None]]


def build_event_handlers(session: AsyncSession) -> dict[str, EventHandlerFn]:
    """Assembles the eventType (SQS `MessageAttributes.eventType`) → processing-function
    dict.

    OutboxConsumer routes the message it receives from SQS through this dict. This single
    function assembles the entire event wiring for all three BCs — Account/Card/Payment —
    covering not just Account's own Domain Event handlers, but also the external BC's
    (Card's) reaction handlers subscribing to Account's published Integration Events
    (account.suspended.v1/account.closed.v1), the handlers converting Payment's own Domain
    Events into Integration Events (PaymentCompleted/PaymentCancelled/RefundApproved), and
    Account's reaction handlers subscribing to those Integration Events
    (payment.completed.v1/payment.cancelled.v1/refund.approved.v1) — since FastAPI has no
    loose inter-module registration mechanism like NestJS's EventHandlerRegistry, this
    function is the composition root for all three BCs. OutboxConsumer calls this function
    for every message and reuses the assembly result.

    The session is opened fresh and passed in by the caller (OutboxConsumer) per message
    (a unit of work) — this function itself only assembles new Repository/Service instances
    on every call, and doesn't manage the session's lifecycle directly (following exactly
    the "one session per unit of work" principle from persistence.md).
    """
    account_repo: AccountRepository = SqlAlchemyAccountRepository(session)
    card_repo: CardRepository = SqlAlchemyCardRepository(session)
    notification_service = SesNotificationService(session)
    card_notification_service = CardSesNotificationService(session)
    outbox_writer = OutboxWriter(session)
    card_integration_event_controller = CardIntegrationEventController(card_repo)
    account_integration_event_controller = AccountIntegrationEventController(account_repo)

    return {
        "AccountCreated": AccountCreatedEventHandler(notification_service).handle,
        "MoneyDeposited": MoneyDepositedEventHandler(notification_service).handle,
        "MoneyWithdrawn": MoneyWithdrawnEventHandler(notification_service).handle,
        "AccountSuspended": AccountSuspendedEventHandler(notification_service, outbox_writer).handle,
        "AccountReactivated": AccountReactivatedEventHandler(notification_service).handle,
        "AccountClosed": AccountClosedEventHandler(notification_service, outbox_writer).handle,
        # The Domain Event the regular interest-payment batch (scheduling.md) publishes via
        # Account.apply_interest() — rides the exact same SES notification path as other
        # Account events.
        "InterestPaid": InterestPaidEventHandler(notification_service).handle,
        "account.suspended.v1": card_integration_event_controller.on_account_suspended,
        "account.closed.v1": card_integration_event_controller.on_account_closed,
        # The Domain Event the monthly card-statement delivery batch publishes via
        # Card.send_statement() — the Card BC's first Domain Event (the same flow as
        # domain-events.md, with its own dedicated CardNotificationService).
        "CardStatementSent": CardStatementSentEventHandler(card_notification_service).handle,
        "PaymentCompleted": PaymentCompletedEventHandler(outbox_writer).handle,
        "PaymentCancelled": PaymentCancelledEventHandler(outbox_writer).handle,
        "RefundApproved": RefundApprovedEventHandler(outbox_writer).handle,
        "payment.completed.v1": account_integration_event_controller.on_payment_completed,
        "payment.cancelled.v1": account_integration_event_controller.on_payment_cancelled,
        "refund.approved.v1": account_integration_event_controller.on_refund_approved,
    }
