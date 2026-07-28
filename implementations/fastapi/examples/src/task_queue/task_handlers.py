from __future__ import annotations

from collections.abc import Awaitable, Callable

from sqlalchemy.ext.asyncio import AsyncSession

from ..account.application.command.analyze_monthly_spending_handler import AnalyzeMonthlySpendingHandler
from ..account.application.command.apply_daily_interest_handler import ApplyDailyInterestHandler
from ..account.application.command.forecast_spending_handler import ForecastSpendingHandler
from ..account.domain.repository import AccountRepository
from ..account.infrastructure.persistence.account_repository import SqlAlchemyAccountRepository
from ..account.infrastructure.persistence.spending_analysis_repository import SqlAlchemySpendingAnalysisRepository
from ..account.infrastructure.persistence.spending_forecast_repository import SqlAlchemySpendingForecastRepository
from ..account.infrastructure.spending_forecast_model_impl import SpendingForecastModelImpl
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
    """Assembles the taskType (SQS `MessageAttributes.taskType`) → processing-function
    dict.

    TaskConsumer routes the message it receives from the Task queue through this dict.
    Unlike the Domain Event's `build_event_handlers()` (src/outbox/event_handlers.py, a
    1:N fan-out), a Task has exactly one handler per taskType (see the "Number of
    handlers" row of the "Task Queue vs Domain Event" table in domain-events.md) — so the
    value type is a single Callable, not a list.

    The session is opened fresh and passed in by the caller (TaskConsumer) per message (a
    unit of work) — this function itself only assembles new Repository/Adapter/Handler
    instances on every call, and doesn't manage the session's lifecycle directly (the same
    principle as event_handlers.py).
    """
    account_repo: AccountRepository = SqlAlchemyAccountRepository(session)
    spending_analysis_repo = SqlAlchemySpendingAnalysisRepository(session)
    spending_forecast_repo = SqlAlchemySpendingForecastRepository(session)
    card_repo: CardRepository = SqlAlchemyCardRepository(session)
    payment_query: PaymentQuery = SqlAlchemyPaymentRepository(session)

    account_adapter: AccountAdapter = AccountAdapterImpl(account_repo)
    payment_adapter: PaymentAdapter = PaymentAdapterImpl(payment_query)

    account_task_controller = AccountTaskController(
        ApplyDailyInterestHandler(account_repo),
        AnalyzeMonthlySpendingHandler(account_repo, spending_analysis_repo),
        ForecastSpendingHandler(
            account_repo, spending_analysis_repo, spending_forecast_repo, SpendingForecastModelImpl()
        ),
    )
    card_task_controller = CardTaskController(
        SendMonthlyCardStatementHandler(card_repo, account_adapter, payment_adapter)
    )

    return {
        "account.interest.apply": account_task_controller.apply_daily_interest,
        "account.analyze-monthly-spending": account_task_controller.analyze_monthly_spending,
        "account.forecast-spending": account_task_controller.forecast_spending,
        "card.statement.send": card_task_controller.send_monthly_statement,
    }
