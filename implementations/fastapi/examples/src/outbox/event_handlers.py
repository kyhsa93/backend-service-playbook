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
    """eventType(SQS `MessageAttributes.eventType`) → 처리 함수 dict를 조립한다.

    OutboxConsumer가 SQS에서 수신한 메시지를 이 dict로 라우팅한다. Account 자신의
    Domain Event 핸들러뿐 아니라, Account가 발행하는 Integration Event
    (account.suspended.v1/account.closed.v1)를 구독하는 외부 BC(Card)의 반응 핸들러,
    Payment 자신의 Domain Event를 Integration Event로 변환하는 핸들러
    (PaymentCompleted/PaymentCancelled/RefundApproved), 그리고 그 Integration Event
    (payment.completed.v1/payment.cancelled.v1/refund.approved.v1)를 구독하는 Account의
    반응 핸들러까지 Account/Card/Payment 세 BC의 이벤트 배선을 이 함수 하나가 조립한다 —
    FastAPI에는 NestJS의 EventHandlerRegistry 같은 모듈 간 느슨한 등록 메커니즘이 없으므로,
    이 함수가 곧 세 BC의 composition root다. OutboxConsumer가 메시지마다 이 함수를 호출해
    조립 결과를 재사용한다.

    세션은 호출자(OutboxConsumer)가 메시지 하나(단위 작업)당 새로 열어 넘긴다 — 이 함수
    자체는 매 호출마다 새 Repository/Service 인스턴스를 조립할 뿐, 세션의 생명주기를
    직접 관리하지 않는다(persistence.md의 "세션은 단위 작업당 하나" 원칙을 그대로 따른다).
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
        # 정기 이자 지급 배치(scheduling.md)가 Account.apply_interest()로 발행하는 Domain Event
        # — 다른 Account 이벤트와 동일한 SES 알림 경로를 그대로 탄다.
        "InterestPaid": InterestPaidEventHandler(notification_service).handle,
        "account.suspended.v1": card_integration_event_controller.on_account_suspended,
        "account.closed.v1": card_integration_event_controller.on_account_closed,
        # 매월 카드 사용내역 발송 배치가 Card.send_statement()로 발행하는 Domain Event —
        # Card BC 최초의 Domain Event다(domain-events.md와 동일한 흐름, 전용 CardNotificationService).
        "CardStatementSent": CardStatementSentEventHandler(card_notification_service).handle,
        "PaymentCompleted": PaymentCompletedEventHandler(outbox_writer).handle,
        "PaymentCancelled": PaymentCancelledEventHandler(outbox_writer).handle,
        "RefundApproved": RefundApprovedEventHandler(outbox_writer).handle,
        "payment.completed.v1": account_integration_event_controller.on_payment_completed,
        "payment.cancelled.v1": account_integration_event_controller.on_payment_cancelled,
        "refund.approved.v1": account_integration_event_controller.on_refund_approved,
    }
