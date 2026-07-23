import asyncio
import os
import re
from collections.abc import AsyncGenerator

import httpx
import pytest
import pytest_asyncio
from conftest import create_domain_event_queue, start_outbox_background_tasks, stop_outbox_background_tasks, wait_until
from httpx import ASGITransport, AsyncClient
from main import app
from opentelemetry import trace
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from sqlalchemy import select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from testcontainers.localstack import LocalStackContainer
from testcontainers.postgres import PostgresContainer

from src.account.infrastructure.notification.sent_email_model import SentEmailModel
from src.account.infrastructure.persistence.account_repository import Base
from src.auth.infrastructure.jwt_auth_service import JwtAuthService
from src.database import get_session
from src.outbox.outbox_model import OutboxModel

OWNER_ID = "owner-1"
RECIPIENT_EMAIL = "owner1@example.com"
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
async def notification_env() -> AsyncGenerator[dict, None]:
    with (
        PostgresContainer("postgres:16-alpine") as postgres,
        LocalStackContainer("localstack/localstack:3.0", region_name="us-east-1").with_services(
            "ses", "sqs"
        ) as localstack,
    ):
        ses_endpoint = localstack.get_url()
        queue_url = create_domain_event_queue(localstack)

        previous_env = {
            key: os.environ.get(key)
            for key in (
                "AWS_REGION",
                "AWS_ENDPOINT_URL",
                "AWS_ACCESS_KEY_ID",
                "AWS_SECRET_ACCESS_KEY",
                "SES_SENDER_EMAIL",
            )
        }
        os.environ["AWS_REGION"] = "us-east-1"
        os.environ["AWS_ENDPOINT_URL"] = ses_endpoint
        os.environ["AWS_ACCESS_KEY_ID"] = "test"
        os.environ["AWS_SECRET_ACCESS_KEY"] = "test"
        os.environ["SES_SENDER_EMAIL"] = SENDER_EMAIL
        os.environ["SQS_DOMAIN_EVENT_QUEUE_URL"] = queue_url

        # LocalStack's SES emulator enforces sender-identity verification, just like real SES,
        # so the sender address must be verified before any send_email call will succeed.
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

        # OutboxPoller/OutboxConsumer delivers Domain Events like AccountCreated/MoneyDeposited
        # to SesNotificationService asynchronously via SQS — main.py's lifespan isn't triggered
        # under ASGITransport, so this fixture starts it in the background directly.
        outbox_tasks = start_outbox_background_tasks(session_factory)

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            yield {"client": client, "ses_endpoint": ses_endpoint, "session_factory": session_factory}

        await stop_outbox_background_tasks(outbox_tasks)
        await engine.dispose()
        app.dependency_overrides.pop(get_session, None)

        for key, value in previous_env.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


async def _find_sent_email(session_factory, account_id: str, event_type: str) -> SentEmailModel | None:
    async with session_factory() as session:
        stmt = select(SentEmailModel).where(
            SentEmailModel.account_id == account_id, SentEmailModel.event_type == event_type
        )
        return (await session.execute(stmt)).scalar_one_or_none()


async def _count_sent_emails(session_factory, account_id: str, event_type: str) -> int:
    async with session_factory() as session:
        stmt = select(SentEmailModel).where(
            SentEmailModel.account_id == account_id, SentEmailModel.event_type == event_type
        )
        return len((await session.execute(stmt)).scalars().all())


async def _mark_unprocessed(session_factory, event_type: str) -> None:
    """Reverts `processed` so it looks as if OutboxPoller hasn't published to SQS yet —
    simulates the at-least-once redelivery where the same event is published to SQS again
    on the next tick and OutboxConsumer receives it again."""
    async with session_factory() as session:
        stmt = select(OutboxModel).where(OutboxModel.event_type == event_type)
        rows = (await session.execute(stmt)).scalars().all()
        for row in rows:
            row.processed = False
        await session.commit()


async def _wait_for_sent_email(session_factory, account_id: str, event_type: str) -> SentEmailModel:
    """Polls until `_find_sent_email()` finds a row — since SesNotificationService is only
    called after OutboxConsumer receives the event from SQS, the email may not be recorded
    yet right after the API response."""
    found: dict[str, SentEmailModel] = {}

    async def _check() -> bool:
        email = await _find_sent_email(session_factory, account_id, event_type)
        if email is None:
            return False
        found["email"] = email
        return True

    await wait_until(_check)
    return found["email"]


@pytest.mark.asyncio
async def test_account_created_sends_ses_email_and_records_it(notification_env: dict) -> None:
    client: AsyncClient = notification_env["client"]

    response = await client.post(
        "/accounts", json={"currency": "KRW", "email": RECIPIENT_EMAIL}, headers=auth_headers(OWNER_ID)
    )
    assert response.status_code == 201
    account_id = response.json()["account_id"]

    sent_email = await _wait_for_sent_email(notification_env["session_factory"], account_id, "AccountCreated")
    assert sent_email.recipient == RECIPIENT_EMAIL
    assert sent_email.ses_message_id

    ses_messages = await _fetch_ses_messages(notification_env["ses_endpoint"])
    matched = next((m for m in ses_messages if m["Id"] == sent_email.ses_message_id), None)
    assert matched is not None
    assert RECIPIENT_EMAIL in matched["Destination"]["ToAddresses"]


@pytest.mark.asyncio
async def test_redelivering_the_same_event_from_outbox_does_not_send_duplicate_email(notification_env: dict) -> None:
    client: AsyncClient = notification_env["client"]

    create_response = await client.post(
        "/accounts", json={"currency": "KRW", "email": RECIPIENT_EMAIL}, headers=auth_headers(OWNER_ID)
    )
    account_id = create_response.json()["account_id"]

    await _wait_for_sent_email(notification_env["session_factory"], account_id, "AccountCreated")
    assert await _count_sent_emails(notification_env["session_factory"], account_id, "AccountCreated") == 1

    # Reverts the processed flag so OutboxPoller re-publishes the AccountCreated event to SQS
    # (simulates at-least-once redelivery) — it gets re-published on the next tick and
    # OutboxConsumer receives it again and calls SesNotificationService.
    await _mark_unprocessed(notification_env["session_factory"], "AccountCreated")

    async def _republished() -> bool:
        async with notification_env["session_factory"]() as session:
            stmt = select(OutboxModel).where(OutboxModel.event_type == "AccountCreated")
            rows = (await session.execute(stmt)).scalars().all()
            return bool(rows) and all(row.processed for row in rows)

    # First confirms that redelivery actually happened (immediately re-checking count==1 alone
    # could pass vacuously before the redelivery has even happened).
    await wait_until(_republished)
    # Gives OutboxConsumer extra time to process the redelivered message.
    await asyncio.sleep(3)

    # Thanks to the ledger (SentEmailModel.outbox_event_id), only one email remains even if the
    # same event is redelivered.
    assert await _count_sent_emails(notification_env["session_factory"], account_id, "AccountCreated") == 1


@pytest.mark.asyncio
async def test_deposit_sends_ses_email_and_records_it(notification_env: dict) -> None:
    client: AsyncClient = notification_env["client"]

    create_response = await client.post(
        "/accounts", json={"currency": "KRW", "email": RECIPIENT_EMAIL}, headers=auth_headers(OWNER_ID)
    )
    account_id = create_response.json()["account_id"]

    deposit_response = await client.post(
        f"/accounts/{account_id}/deposit", json={"amount": 10000}, headers=auth_headers(OWNER_ID)
    )
    assert deposit_response.status_code == 201

    sent_email = await _wait_for_sent_email(notification_env["session_factory"], account_id, "MoneyDeposited")
    assert sent_email.recipient == RECIPIENT_EMAIL

    ses_messages = await _fetch_ses_messages(notification_env["ses_endpoint"])
    matched = next((m for m in ses_messages if m["Id"] == sent_email.ses_message_id), None)
    assert matched is not None


@pytest.mark.asyncio
async def test_traceparent_propagates_from_http_request_through_outbox_to_event_processing(
    notification_env: dict,
) -> None:
    """Verifies observability.md's "one trace end-to-end" claim for real, not just that a
    string gets copied around. `main.py` already called `configure_tracing()` at import time
    (module-level, before this fixture even runs), so there's a real global `TracerProvider`
    to attach an in-memory exporter to — this test captures every span the process emits
    for this one request/event round trip and asserts the span FastAPIInstrumentor created
    for `POST /accounts` and the `outbox.process_event` span OutboxConsumer starts while
    replaying the resulting AccountCreated event from SQS share the same trace_id. That's
    the actual claim: an HTTP request and its async Outbox-driven event processing landing
    in one trace, not two disconnected ones.
    """
    client: AsyncClient = notification_env["client"]

    exporter = InMemorySpanExporter()
    trace.get_tracer_provider().add_span_processor(SimpleSpanProcessor(exporter))

    response = await client.post(
        "/accounts", json={"currency": "KRW", "email": RECIPIENT_EMAIL}, headers=auth_headers(OWNER_ID)
    )
    assert response.status_code == 201
    account_id = response.json()["account_id"]

    await _wait_for_sent_email(notification_env["session_factory"], account_id, "AccountCreated")

    async def _fetch_outbox_row() -> OutboxModel | None:
        async with notification_env["session_factory"]() as session:
            stmt = select(OutboxModel).where(
                OutboxModel.event_type == "AccountCreated", OutboxModel.payload.contains(account_id)
            )
            return (await session.execute(stmt)).scalars().first()

    async def _outbox_row_has_trace_parent() -> bool:
        row = await _fetch_outbox_row()
        return row is not None and bool(row.trace_parent)

    # Same generous timeout as _wait_for_sent_email — the outbox row itself is written
    # synchronously with the API response, but this only checks it's been written *and*
    # carries a trace_parent (i.e. the request really was inside an active span).
    await wait_until(_outbox_row_has_trace_parent)
    outbox_row = await _fetch_outbox_row()
    assert outbox_row is not None
    assert re.match(r"^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$", outbox_row.trace_parent)
    outbox_row_trace_id = outbox_row.trace_parent.split("-")[1]

    finished_spans = exporter.get_finished_spans()
    http_span = next(
        (s for s in finished_spans if s.attributes.get("http.method") == "POST" and s.parent is None),
        None,
    )
    outbox_span = next((s for s in finished_spans if s.name == "outbox.process_event"), None)

    assert http_span is not None, "FastAPIInstrumentor should have emitted a root span for POST /accounts"
    assert outbox_span is not None, "OutboxConsumer should have started an outbox.process_event span"
    assert format(http_span.context.trace_id, "032x") == outbox_row_trace_id
    assert http_span.context.trace_id == outbox_span.context.trace_id, (
        "the Outbox hop must land in the same trace as the HTTP request that produced it"
    )
