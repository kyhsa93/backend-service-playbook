import asyncio
import os
from collections.abc import AsyncGenerator

import httpx
import pytest
import pytest_asyncio
from conftest import create_domain_event_queue, start_outbox_background_tasks, stop_outbox_background_tasks, wait_until
from httpx import ASGITransport, AsyncClient
from main import app
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

        # OutboxPoller/OutboxConsumer가 AccountCreated/MoneyDeposited 같은 Domain Event를
        # SQS를 거쳐 비동기로 SesNotificationService에 전달한다 — main.py의 lifespan은
        # ASGITransport에서 트리거되지 않으므로 이 fixture가 직접 백그라운드로 띄운다.
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
    """OutboxPoller가 아직 SQS에 발행하지 않은 것처럼 보이도록 `processed`를 되돌린다 —
    다음 tick에서 같은 이벤트가 SQS로 다시 발행되고 OutboxConsumer가 다시 수신하는
    at-least-once 재전달을 시뮬레이션한다."""
    async with session_factory() as session:
        stmt = select(OutboxModel).where(OutboxModel.event_type == event_type)
        rows = (await session.execute(stmt)).scalars().all()
        for row in rows:
            row.processed = False
        await session.commit()


async def _wait_for_sent_email(session_factory, account_id: str, event_type: str) -> SentEmailModel:
    """`_find_sent_email()`이 행을 찾을 때까지 폴링한다 — SesNotificationService는
    OutboxConsumer가 SQS에서 이벤트를 수신한 뒤에야 호출되므로, API 응답 직후에는
    아직 이메일이 기록되어 있지 않을 수 있다."""
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
async def test_outbox가_같은_이벤트를_재전달해도_이메일이_중복_발송되지_않는다(notification_env: dict) -> None:
    client: AsyncClient = notification_env["client"]

    create_response = await client.post(
        "/accounts", json={"currency": "KRW", "email": RECIPIENT_EMAIL}, headers=auth_headers(OWNER_ID)
    )
    account_id = create_response.json()["account_id"]

    await _wait_for_sent_email(notification_env["session_factory"], account_id, "AccountCreated")
    assert await _count_sent_emails(notification_env["session_factory"], account_id, "AccountCreated") == 1

    # OutboxPoller가 AccountCreated 이벤트를 다시 SQS로 발행하도록 processed 플래그를
    # 되돌린다(at-least-once 재전달 시뮬레이션) — 다음 tick에서 재발행되고 OutboxConsumer가
    # 다시 수신해 SesNotificationService를 호출한다.
    await _mark_unprocessed(notification_env["session_factory"], "AccountCreated")

    async def _republished() -> bool:
        async with notification_env["session_factory"]() as session:
            stmt = select(OutboxModel).where(OutboxModel.event_type == "AccountCreated")
            rows = (await session.execute(stmt)).scalars().all()
            return bool(rows) and all(row.processed for row in rows)

    # 재전달이 실제로 일어났음을 먼저 확인한다(그냥 count==1을 곧바로 재확인하면, 재전달이
    # 아직 일어나기도 전에 우연히 통과하는 vacuous pass가 될 수 있다).
    await wait_until(_republished)
    # OutboxConsumer가 재전달된 메시지를 처리할 시간을 추가로 준다.
    await asyncio.sleep(3)

    # Ledger(SentEmailModel.outbox_event_id) 덕분에 같은 이벤트가 재전달돼도 이메일은 한 번만 남는다.
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
