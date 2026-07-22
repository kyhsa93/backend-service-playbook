import asyncio
import os
import uuid
from collections.abc import AsyncGenerator
from datetime import date

import httpx
import pytest
import pytest_asyncio
from conftest import (
    create_domain_event_queue,
    create_task_queue,
    start_outbox_background_tasks,
    start_task_queue_background_tasks,
    stop_outbox_background_tasks,
    stop_task_queue_background_tasks,
    wait_until,
)
from httpx import ASGITransport, AsyncClient
from main import app
from sqlalchemy import select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from testcontainers.localstack import LocalStackContainer
from testcontainers.postgres import PostgresContainer

from src.account.infrastructure.notification.sent_email_model import SentEmailModel
from src.account.infrastructure.persistence.account_repository import AccountModel, Base
from src.auth.infrastructure.jwt_auth_service import JwtAuthService
from src.card.application.command.send_monthly_card_statement_handler import previous_month_period
from src.card.infrastructure.notification.sent_statement_email_model import SentStatementEmailModel
from src.card.infrastructure.persistence.card_repository import CardModel
from src.database import get_session
from src.payment.infrastructure.persistence.payment_repository import PaymentModel
from src.task_queue.task_outbox_writer import TaskOutboxWriter

OWNER_ID = "owner-1"
OWNER_EMAIL = "owner1@example.com"
SENDER_EMAIL = "no-reply@backend-service-playbook.example.com"


def auth_headers(user_id: str) -> dict:
    token = JwtAuthService().issue_token(user_id)
    return {"Authorization": f"Bearer {token}"}


async def _fetch_ses_messages(endpoint: str) -> list[dict]:
    async with httpx.AsyncClient() as http_client:
        response = await http_client.get(f"{endpoint}/_aws/ses")
        response.raise_for_status()
        return response.json()["messages"]


@pytest_asyncio.fixture(scope="module")
async def scheduling_env() -> AsyncGenerator[dict, None]:
    with (
        PostgresContainer("postgres:16-alpine") as postgres,
        LocalStackContainer("localstack/localstack:3.0", region_name="us-east-1").with_services(
            "ses", "sqs"
        ) as localstack,
    ):
        ses_endpoint = localstack.get_url()
        domain_event_queue_url = create_domain_event_queue(localstack)
        task_queue_url = create_task_queue(localstack)

        previous_env = {
            key: os.environ.get(key)
            for key in (
                "AWS_REGION",
                "AWS_ENDPOINT_URL",
                "AWS_ACCESS_KEY_ID",
                "AWS_SECRET_ACCESS_KEY",
                "SES_SENDER_EMAIL",
                "SQS_DOMAIN_EVENT_QUEUE_URL",
                "SQS_TASK_QUEUE_URL",
            )
        }
        os.environ["AWS_REGION"] = "us-east-1"
        os.environ["AWS_ENDPOINT_URL"] = ses_endpoint
        os.environ["AWS_ACCESS_KEY_ID"] = "test"
        os.environ["AWS_SECRET_ACCESS_KEY"] = "test"
        os.environ["SES_SENDER_EMAIL"] = SENDER_EMAIL
        os.environ["SQS_DOMAIN_EVENT_QUEUE_URL"] = domain_event_queue_url
        os.environ["SQS_TASK_QUEUE_URL"] = task_queue_url

        verification_client = localstack.get_client("ses")
        verification_client.verify_email_identity(EmailAddress=SENDER_EMAIL)

        url = postgres.get_connection_url().replace("postgresql+psycopg2", "postgresql+asyncpg")
        engine = create_async_engine(url)

        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)

        session_factory = async_sessionmaker(engine, expire_on_commit=False)

        async def override_get_session():
            async with session_factory() as session:
                yield session
                await session.commit()

        app.dependency_overrides[get_session] = override_get_session

        # main.py's lifespan (including starting APScheduler) isn't triggered under this
        # ASGITransport-driven client — this fixture starts the Outbox/Task pipeline directly
        # in the background, and each test triggers the two scheduled paths by calling
        # TaskOutboxWriter.enqueue() directly instead of waiting for a real Cron tick
        # (requirement: "call the enqueue method directly").
        outbox_tasks = start_outbox_background_tasks(session_factory)
        task_queue_tasks = start_task_queue_background_tasks(session_factory)

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            yield {"client": client, "ses_endpoint": ses_endpoint, "session_factory": session_factory}

        await stop_outbox_background_tasks(outbox_tasks)
        await stop_task_queue_background_tasks(task_queue_tasks)
        await engine.dispose()
        app.dependency_overrides.pop(get_session, None)

        for key, value in previous_env.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


async def _enqueue_task(session_factory, task_type: str, group_id: str) -> None:
    """Reproduces exactly what the Scheduler (Cron) does — instead of waiting for a real
    Cron tick, calls TaskOutboxWriter.enqueue() directly to load the Task with the same
    transaction commit (the same path as "The Task Outbox pattern" in scheduling.md, with
    the test standing in only for the trigger)."""
    async with session_factory() as session:
        await TaskOutboxWriter(session).enqueue(
            task_type=task_type,
            payload={},
            group_id=group_id,
            deduplication_id=f"{task_type}-test-{uuid.uuid4().hex}",
        )
        await session.commit()


async def _get_account_row(session_factory, account_id: str) -> AccountModel:
    async with session_factory() as session:
        return await session.get(AccountModel, account_id)


async def _get_card_row(session_factory, card_id: str) -> CardModel:
    async with session_factory() as session:
        return await session.get(CardModel, card_id)


@pytest.mark.asyncio
async def test_enqueueing_the_regular_interest_payment_task_pays_interest_to_active_accounts_and_sends_email(
    scheduling_env: dict,
) -> None:
    client: AsyncClient = scheduling_env["client"]
    session_factory = scheduling_env["session_factory"]

    create_response = await client.post(
        "/accounts", json={"currency": "KRW", "email": OWNER_EMAIL}, headers=auth_headers(OWNER_ID)
    )
    account_id = create_response.json()["account_id"]
    await client.post(f"/accounts/{account_id}/deposit", json={"amount": 1000000}, headers=auth_headers(OWNER_ID))

    await _enqueue_task(session_factory, "account.interest.apply", "account.interest")

    async def _interest_applied() -> bool:
        row = await _get_account_row(session_factory, account_id)
        return row is not None and row.last_interest_paid_at == date.today()

    await wait_until(_interest_applied, timeout=30.0)

    row = await _get_account_row(session_factory, account_id)
    # 1_000_000 * 0.0001 (the default daily interest rate) = 100
    assert row.amount == 1000100

    async def _interest_email_sent() -> bool:
        async with session_factory() as session:
            stmt = select(SentEmailModel).where(
                SentEmailModel.account_id == account_id, SentEmailModel.event_type == "InterestPaid"
            )
            return (await session.execute(stmt)).scalar_one_or_none() is not None

    await wait_until(_interest_email_sent, timeout=30.0)

    ses_messages = await _fetch_ses_messages(scheduling_env["ses_endpoint"])
    assert any(OWNER_EMAIL in m["Destination"]["ToAddresses"] for m in ses_messages)


@pytest.mark.asyncio
async def test_redelivering_the_regular_interest_payment_task_does_not_pay_interest_twice_on_the_same_day(
    scheduling_env: dict,
) -> None:
    client: AsyncClient = scheduling_env["client"]
    session_factory = scheduling_env["session_factory"]

    create_response = await client.post(
        "/accounts", json={"currency": "KRW", "email": OWNER_EMAIL}, headers=auth_headers(OWNER_ID)
    )
    account_id = create_response.json()["account_id"]
    await client.post(f"/accounts/{account_id}/deposit", json={"amount": 500000}, headers=auth_headers(OWNER_ID))

    await _enqueue_task(session_factory, "account.interest.apply", "account.interest")

    async def _interest_applied() -> bool:
        row = await _get_account_row(session_factory, account_id)
        return row is not None and row.last_interest_paid_at == date.today()

    await wait_until(_interest_applied, timeout=30.0)
    balance_after_first_run = (await _get_account_row(session_factory, account_id)).amount

    # Enqueues again as if the Task were redelivered on the same day (at-least-once) — uses a
    # different deduplication_id so it doesn't rely on the FIFO queue's own dedup, verifying
    # safety comes purely from the Aggregate-level Level 1 idempotency
    # (Account.last_interest_paid_at).
    await _enqueue_task(session_factory, "account.interest.apply", "account.interest")

    # Gives reprocessing time to actually happen, then confirms the balance is still unchanged.

    await asyncio.sleep(5)
    row = await _get_account_row(session_factory, account_id)
    assert row.amount == balance_after_first_run


@pytest.mark.asyncio
async def test_enqueueing_the_monthly_card_statement_task_aggregates_last_months_payments_and_sends_email(
    scheduling_env: dict,
) -> None:
    client: AsyncClient = scheduling_env["client"]
    session_factory = scheduling_env["session_factory"]

    create_response = await client.post(
        "/accounts", json={"currency": "KRW", "email": OWNER_EMAIL}, headers=auth_headers(OWNER_ID)
    )
    account_id = create_response.json()["account_id"]

    card_response = await client.post(
        "/cards", json={"account_id": account_id, "brand": "VISA"}, headers=auth_headers(OWNER_ID)
    )
    card_id = card_response.json()["card_id"]

    # If a payment is made via the API, its created_at becomes "now" (this month), so it won't
    # fall into the "last month" aggregation window — to populate real data within the last
    # full month the batch aggregates, this directly loads 2 COMPLETED payments with a
    # created_at inside that window straight into the session (the same approach other e2e
    # tests already use when directly manipulating OutboxModel.processed).
    period, since, _until = previous_month_period(date.today())
    async with session_factory() as session:
        session.add(
            PaymentModel(
                id=uuid.uuid4().hex,
                card_id=card_id,
                account_id=account_id,
                owner_id=OWNER_ID,
                amount=7000,
                status="COMPLETED",
                created_at=since,
            )
        )
        session.add(
            PaymentModel(
                id=uuid.uuid4().hex,
                card_id=card_id,
                account_id=account_id,
                owner_id=OWNER_ID,
                amount=3000,
                status="COMPLETED",
                created_at=since,
            )
        )
        await session.commit()

    await _enqueue_task(session_factory, "card.statement.send", "card.statement")

    async def _statement_sent() -> bool:
        row = await _get_card_row(session_factory, card_id)
        return row is not None and row.last_statement_sent_month == period

    await wait_until(_statement_sent, timeout=30.0)

    async def _statement_email_recorded() -> bool:
        async with session_factory() as session:
            stmt = select(SentStatementEmailModel).where(SentStatementEmailModel.card_id == card_id)
            return (await session.execute(stmt)).scalar_one_or_none() is not None

    await wait_until(_statement_email_recorded, timeout=30.0)

    ses_messages = await _fetch_ses_messages(scheduling_env["ses_endpoint"])
    assert any(OWNER_EMAIL in m["Destination"]["ToAddresses"] for m in ses_messages)

    # Confirms the aggregation period is reflected in the subject of the actual delivery
    # ledger — the exact calculation logic for the count/total itself is verified by
    # PaymentAdapterImpl/SendMonthlyCardStatementHandler's unit tests, so this e2e test focuses
    # on confirming the whole pipeline actually works (enqueue -> Task queue -> Consumer ->
    # Handler -> Domain Event -> Outbox -> queue -> Consumer -> SES send + ledger record).
    async with session_factory() as session:
        stmt = select(SentStatementEmailModel).where(SentStatementEmailModel.card_id == card_id)
        sent = (await session.execute(stmt)).scalar_one()
        assert period in sent.subject
        assert sent.recipient == OWNER_EMAIL


@pytest.mark.asyncio
async def test_a_card_already_sent_this_month_is_not_sent_again_even_if_the_task_is_redelivered(
    scheduling_env: dict,
) -> None:
    client: AsyncClient = scheduling_env["client"]
    session_factory = scheduling_env["session_factory"]

    create_response = await client.post(
        "/accounts", json={"currency": "KRW", "email": OWNER_EMAIL}, headers=auth_headers(OWNER_ID)
    )
    account_id = create_response.json()["account_id"]
    card_response = await client.post(
        "/cards", json={"account_id": account_id, "brand": "VISA"}, headers=auth_headers(OWNER_ID)
    )
    card_id = card_response.json()["card_id"]

    period, _since, _until = previous_month_period(date.today())

    await _enqueue_task(session_factory, "card.statement.send", "card.statement")

    async def _statement_sent() -> bool:
        row = await _get_card_row(session_factory, card_id)
        return row is not None and row.last_statement_sent_month == period

    await wait_until(_statement_sent, timeout=30.0)

    async def _sent_email_count() -> int:
        async with session_factory() as session:
            stmt = select(SentStatementEmailModel).where(SentStatementEmailModel.card_id == card_id)
            return len((await session.execute(stmt)).scalars().all())

    async def _email_recorded_at_least_once() -> bool:
        return await _sent_email_count() > 0

    # The moment card.last_statement_sent_month is updated is only the moment the
    # CardStatementSent event is "written" to the Outbox — the SES send (the Outbox -> SQS ->
    # OutboxConsumer round trip) may not have finished yet, so this waits separately until the
    # ledger row actually exists (to avoid a vacuous pass).
    await wait_until(_email_recorded_at_least_once, timeout=30.0)

    first_count = await _sent_email_count()
    assert first_count == 1

    # Simulates redelivery as if the Task were enqueued again in the same month (at-least-once).
    await _enqueue_task(session_factory, "card.statement.send", "card.statement")

    await asyncio.sleep(5)
    assert await _sent_email_count() == first_count
