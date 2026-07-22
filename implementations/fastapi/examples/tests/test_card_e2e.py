import os
from collections.abc import AsyncGenerator

import pytest
import pytest_asyncio
from conftest import create_domain_event_queue, start_outbox_background_tasks, stop_outbox_background_tasks, wait_until
from httpx import ASGITransport, AsyncClient
from main import app
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from testcontainers.localstack import LocalStackContainer
from testcontainers.postgres import PostgresContainer

from src.account.infrastructure.persistence.account_repository import Base
from src.auth.infrastructure.jwt_auth_service import JwtAuthService
from src.database import get_session

OWNER_ID = "owner-1"
OTHER_OWNER_ID = "owner-2"
OWNER_EMAIL = "owner1@example.com"


def auth_headers(user_id: str) -> dict:
    token = JwtAuthService().issue_token(user_id)
    return {"Authorization": f"Bearer {token}"}


@pytest_asyncio.fixture(scope="session")
async def client() -> AsyncGenerator[AsyncClient, None]:
    with (
        PostgresContainer("postgres:16-alpine") as postgres,
        LocalStackContainer("localstack/localstack:3.0", region_name="us-east-1").with_services("sqs") as localstack,
    ):
        queue_url = create_domain_event_queue(localstack)

        previous_env = {
            key: os.environ.get(key)
            for key in ("AWS_REGION", "AWS_ENDPOINT_URL", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY")
        }
        os.environ["AWS_REGION"] = "us-east-1"
        os.environ["AWS_ENDPOINT_URL"] = localstack.get_url()
        os.environ["AWS_ACCESS_KEY_ID"] = "test"
        os.environ["AWS_SECRET_ACCESS_KEY"] = "test"
        os.environ["SQS_DOMAIN_EVENT_QUEUE_URL"] = queue_url

        url = postgres.get_connection_url().replace("postgresql+psycopg2", "postgresql+asyncpg")
        engine = create_async_engine(url)

        async with engine.begin() as conn:
            # Base.metadata already has accounts/cards/outbox/... all registered — main.py
            # imports outbox_consumer, and outbox_consumer imports all the way down into Card's
            # infrastructure via build_event_handlers(), which registers CardModel on the same
            # Base too, so this single create_all() also creates the cards table without
            # needing to import the Card model separately.
            await conn.run_sync(Base.metadata.create_all)

        session_factory = async_sessionmaker(engine, expire_on_commit=False)

        async def override_get_session():
            async with session_factory() as session:
                yield session
                await session.commit()

        app.dependency_overrides[get_session] = override_get_session

        # main.py's lifespan isn't triggered under this ASGITransport-driven client, so this
        # fixture starts OutboxPoller/OutboxConsumer directly — passing through the
        # session_factory that points at this test's ephemeral Postgres/LocalStack.
        outbox_tasks = start_outbox_background_tasks(session_factory)

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as ac:
            yield ac

        await stop_outbox_background_tasks(outbox_tasks)
        await engine.dispose()
        app.dependency_overrides.pop(get_session, None)

        for key, value in previous_env.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


async def create_account(client: AsyncClient, owner_id: str, currency: str = "KRW", email: str = OWNER_EMAIL) -> dict:
    response = await client.post(
        "/accounts", json={"currency": currency, "email": email}, headers=auth_headers(owner_id)
    )
    assert response.status_code == 201
    return response.json()


async def issue_card(client: AsyncClient, owner_id: str, account_id: str, brand: str = "VISA") -> dict:
    response = await client.post(
        "/cards", json={"account_id": account_id, "brand": brand}, headers=auth_headers(owner_id)
    )
    assert response.status_code == 201
    return response.json()


# --- Synchronous Adapter (ACL) flow: POST /cards immediately queries the Account BC ---


@pytest.mark.asyncio
async def test_issue_card_success(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID)

    response = await client.post(
        "/cards", json={"account_id": account["account_id"], "brand": "VISA"}, headers=auth_headers(OWNER_ID)
    )

    assert response.status_code == 201
    body = response.json()
    assert body["account_id"] == account["account_id"]
    assert body["owner_id"] == OWNER_ID
    assert body["brand"] == "VISA"
    assert body["status"] == "ACTIVE"
    assert body["card_id"]
    assert body["created_at"]


@pytest.mark.asyncio
async def test_issue_card_linked_account_not_found_returns_404(client: AsyncClient) -> None:
    response = await client.post(
        "/cards", json={"account_id": "non-existent", "brand": "VISA"}, headers=auth_headers(OWNER_ID)
    )

    assert response.status_code == 404
    body = response.json()
    assert body["statusCode"] == 404
    assert body["code"] == "LINKED_ACCOUNT_NOT_FOUND"
    assert body["error"] == "Not Found"


@pytest.mark.asyncio
async def test_issue_card_other_owners_account_returns_404(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID)

    response = await client.post(
        "/cards",
        json={"account_id": account["account_id"], "brand": "VISA"},
        headers=auth_headers(OTHER_OWNER_ID),
    )

    # AccountAdapter looks up by the (account_id, owner_id) pair, so to another owner this
    # looks identical to "account not found" — the same isolation approach as the Account BC's
    # find_accounts(account_id, owner_id, take=1).
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_issue_card_suspended_account_returns_400(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID)
    await client.post(f"/accounts/{account['account_id']}/suspend", headers=auth_headers(OWNER_ID))

    response = await client.post(
        "/cards", json={"account_id": account["account_id"], "brand": "VISA"}, headers=auth_headers(OWNER_ID)
    )

    assert response.status_code == 400
    body = response.json()
    assert body["statusCode"] == 400
    assert body["code"] == "CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT"
    assert body["error"] == "Bad Request"


@pytest.mark.asyncio
async def test_get_card_success(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID)
    card = await issue_card(client, OWNER_ID, account["account_id"])

    response = await client.get(f"/cards/{card['card_id']}", headers=auth_headers(OWNER_ID))

    assert response.status_code == 200
    body = response.json()
    assert body["card_id"] == card["card_id"]
    assert body["status"] == "ACTIVE"


@pytest.mark.asyncio
async def test_get_card_not_found_returns_404(client: AsyncClient) -> None:
    response = await client.get("/cards/non-existent", headers=auth_headers(OWNER_ID))

    assert response.status_code == 404
    body = response.json()
    assert body["statusCode"] == 404
    assert body["code"] == "CARD_NOT_FOUND"
    assert body["error"] == "Not Found"


@pytest.mark.asyncio
async def test_get_card_other_owner_returns_404(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID)
    card = await issue_card(client, OWNER_ID, account["account_id"])

    response = await client.get(f"/cards/{card['card_id']}", headers=auth_headers(OTHER_OWNER_ID))

    assert response.status_code == 404


# --- Asynchronous Integration Event flow: Account suspension/closure propagates to Card
# asynchronously via OutboxPoller/OutboxConsumer — it may not have propagated yet right after
# the response, so this polls with wait_until() (see conftest.py). ---


async def get_card_status(client: AsyncClient, owner_id: str, card_id: str) -> str:
    response = await client.get(f"/cards/{card_id}", headers=auth_headers(owner_id))
    return response.json()["status"]


@pytest.mark.asyncio
async def test_suspending_account_cascades_to_suspend_its_active_cards(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID)
    card = await issue_card(client, OWNER_ID, account["account_id"])
    assert card["status"] == "ACTIVE"

    suspend_response = await client.post(f"/accounts/{account['account_id']}/suspend", headers=auth_headers(OWNER_ID))
    assert suspend_response.status_code == 204

    # The account.suspended.v1 Integration Event is processed asynchronously through
    # OutboxPoller -> SQS -> OutboxConsumer, changing the card's status — it may not be
    # reflected immediately right after the response.
    await wait_until(lambda: _status_is(client, OWNER_ID, card["card_id"], "SUSPENDED"))


@pytest.mark.asyncio
async def test_suspending_account_does_not_affect_cards_of_other_accounts(client: AsyncClient) -> None:
    account_a = await create_account(client, OWNER_ID)
    account_b = await create_account(client, OWNER_ID)
    card_a = await issue_card(client, OWNER_ID, account_a["account_id"])
    card_b = await issue_card(client, OWNER_ID, account_b["account_id"])

    await client.post(f"/accounts/{account_a['account_id']}/suspend", headers=auth_headers(OWNER_ID))

    await wait_until(lambda: _status_is(client, OWNER_ID, card_a["card_id"], "SUSPENDED"))
    assert await get_card_status(client, OWNER_ID, card_b["card_id"]) == "ACTIVE"


@pytest.mark.asyncio
async def test_closing_account_cascades_to_cancel_its_cards(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID)
    card = await issue_card(client, OWNER_ID, account["account_id"])

    close_response = await client.post(f"/accounts/{account['account_id']}/close", headers=auth_headers(OWNER_ID))
    assert close_response.status_code == 204

    await wait_until(lambda: _status_is(client, OWNER_ID, card["card_id"], "CANCELLED"))


@pytest.mark.asyncio
async def test_closing_account_cancels_already_suspended_cards_too(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID)
    card = await issue_card(client, OWNER_ID, account["account_id"])
    await client.post(f"/accounts/{account['account_id']}/suspend", headers=auth_headers(OWNER_ID))
    await wait_until(lambda: _status_is(client, OWNER_ID, card["card_id"], "SUSPENDED"))

    # Reactivating the account does not publish an Integration Event that affects the card —
    # the card stays SUSPENDED (there's no event like account.reactivated.v1 at all).
    # Only the Account becomes ACTIVE again, so the second suspend right after is a valid
    # state transition.
    await client.post(f"/accounts/{account['account_id']}/reactivate", headers=auth_headers(OWNER_ID))
    await client.post(f"/accounts/{account['account_id']}/suspend", headers=auth_headers(OWNER_ID))

    close_response = await client.post(f"/accounts/{account['account_id']}/close", headers=auth_headers(OWNER_ID))
    assert close_response.status_code == 204

    await wait_until(lambda: _status_is(client, OWNER_ID, card["card_id"], "CANCELLED"))


async def _status_is(client: AsyncClient, owner_id: str, card_id: str, expected: str) -> bool:
    return await get_card_status(client, owner_id, card_id) == expected


@pytest.mark.asyncio
async def test_issuing_a_card_against_a_suspended_then_reactivated_account_succeeds(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID)
    await client.post(f"/accounts/{account['account_id']}/suspend", headers=auth_headers(OWNER_ID))
    await client.post(f"/accounts/{account['account_id']}/reactivate", headers=auth_headers(OWNER_ID))

    response = await client.post(
        "/cards", json={"account_id": account["account_id"], "brand": "VISA"}, headers=auth_headers(OWNER_ID)
    )

    assert response.status_code == 201
    assert response.json()["status"] == "ACTIVE"
