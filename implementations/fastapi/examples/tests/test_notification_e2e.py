import os
from collections.abc import AsyncGenerator

import httpx
import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from testcontainers.localstack import LocalStackContainer
from testcontainers.postgres import PostgresContainer

from main import app
from src.account.infrastructure.notification.sent_email_model import SentEmailModel
from src.account.infrastructure.persistence.account_repository import Base
from src.database import get_session

OWNER_ID = "owner-1"
RECIPIENT_EMAIL = "owner1@example.com"
SENDER_EMAIL = "no-reply@backend-service-playbook.example.com"


async def _fetch_ses_messages(endpoint: str) -> list[dict]:
    async with httpx.AsyncClient() as http_client:
        response = await http_client.get(f"{endpoint}/_aws/ses")
        response.raise_for_status()
        return response.json()["messages"]


@pytest_asyncio.fixture(scope="module")
async def notification_env() -> AsyncGenerator[dict, None]:
    with (
        PostgresContainer("postgres:16-alpine") as postgres,
        LocalStackContainer("localstack/localstack:3.0", region_name="us-east-1").with_services("ses") as localstack,
    ):
        ses_endpoint = localstack.get_url()

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

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            yield {"client": client, "ses_endpoint": ses_endpoint, "session_factory": session_factory}

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


@pytest.mark.asyncio
async def test_account_created_sends_ses_email_and_records_it(notification_env: dict) -> None:
    client: AsyncClient = notification_env["client"]

    response = await client.post(
        "/accounts", json={"currency": "KRW", "email": RECIPIENT_EMAIL}, headers={"X-User-Id": OWNER_ID}
    )
    assert response.status_code == 201
    account_id = response.json()["account_id"]

    sent_email = await _find_sent_email(notification_env["session_factory"], account_id, "AccountCreated")
    assert sent_email is not None
    assert sent_email.recipient == RECIPIENT_EMAIL
    assert sent_email.ses_message_id

    ses_messages = await _fetch_ses_messages(notification_env["ses_endpoint"])
    matched = next((m for m in ses_messages if m["Id"] == sent_email.ses_message_id), None)
    assert matched is not None
    assert RECIPIENT_EMAIL in matched["Destination"]["ToAddresses"]


@pytest.mark.asyncio
async def test_deposit_sends_ses_email_and_records_it(notification_env: dict) -> None:
    client: AsyncClient = notification_env["client"]

    create_response = await client.post(
        "/accounts", json={"currency": "KRW", "email": RECIPIENT_EMAIL}, headers={"X-User-Id": OWNER_ID}
    )
    account_id = create_response.json()["account_id"]

    deposit_response = await client.post(
        f"/accounts/{account_id}/deposit", json={"amount": 10000}, headers={"X-User-Id": OWNER_ID}
    )
    assert deposit_response.status_code == 201

    sent_email = await _find_sent_email(notification_env["session_factory"], account_id, "MoneyDeposited")
    assert sent_email is not None
    assert sent_email.recipient == RECIPIENT_EMAIL

    ses_messages = await _fetch_ses_messages(notification_env["ses_endpoint"])
    matched = next((m for m in ses_messages if m["Id"] == sent_email.ses_message_id), None)
    assert matched is not None
