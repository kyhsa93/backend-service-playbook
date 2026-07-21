from __future__ import annotations

from collections.abc import Awaitable, Callable

from sqlalchemy.ext.asyncio import AsyncSession

from ..account.application.command.apply_daily_interest_handler import ApplyDailyInterestHandler
from ..account.domain.repository import AccountRepository
from ..account.infrastructure.persistence.account_repository import SqlAlchemyAccountRepository
from ..account.interface.task.account_task_controller import AccountTaskController
from ..card.application.adapter.account_adapter import AccountAdapter
from ..card.application.adapter.payment_adapter import PaymentAdapter
from ..card.application.command.send_monthly_card_statement_handler import SendMonthlyCardStatementHandler
from ..card.domain.repository import CardRepository
from ..card.infrastructure.account_adapter_impl import AccountAdapterImpl
from ..card.infrastructure.payment_adapter_impl import PaymentAdapterImpl
from ..card.infrastructure.persistence.card_repository import SqlAlchemyCardRepository
from ..card.interface.task.card_task_controller import CardTaskController
from ..payment.domain.payment_repository import PaymentQuery
from ..payment.infrastructure.persistence.payment_repository import SqlAlchemyPaymentRepository

TaskHandlerFn = Callable[[dict], Awaitable[None]]


def build_task_handlers(session: AsyncSession) -> dict[str, TaskHandlerFn]:
    """taskType(SQS `MessageAttributes.taskType`) → 처리 함수 dict를 조립한다.

    TaskConsumer가 Task 큐에서 수신한 메시지를 이 dict로 라우팅한다. Domain Event의
    `build_event_handlers()`(src/outbox/event_handlers.py, 1:N 팬아웃)와 달리 Task는
    taskType당 정확히 하나의 핸들러만 가진다(domain-events.md "Task Queue vs Domain Event"
    표의 "핸들러 수" 행) — 그래서 값 타입이 list가 아니라 단일 Callable이다.

    세션은 호출자(TaskConsumer)가 메시지 하나(단위 작업)당 새로 열어 넘긴다 — 이 함수
    자체는 매 호출마다 새 Repository/Adapter/Handler 인스턴스를 조립할 뿐, 세션의
    생명주기를 직접 관리하지 않는다(event_handlers.py와 동일한 원칙).
    """
    account_repo: AccountRepository = SqlAlchemyAccountRepository(session)
    card_repo: CardRepository = SqlAlchemyCardRepository(session)
    payment_query: PaymentQuery = SqlAlchemyPaymentRepository(session)

    account_adapter: AccountAdapter = AccountAdapterImpl(account_repo)
    payment_adapter: PaymentAdapter = PaymentAdapterImpl(payment_query)

    account_task_controller = AccountTaskController(ApplyDailyInterestHandler(account_repo))
    card_task_controller = CardTaskController(
        SendMonthlyCardStatementHandler(card_repo, account_adapter, payment_adapter)
    )

    return {
        "account.interest.apply": account_task_controller.apply_daily_interest,
        "card.statement.send": card_task_controller.send_monthly_statement,
    }
