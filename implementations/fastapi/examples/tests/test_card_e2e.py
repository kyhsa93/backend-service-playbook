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
            # Base.metadata에는 accounts/cards/outbox/... 가 모두 등록되어 있다 — main.py가
            # outbox_consumer를 import하고, outbox_consumer가 build_event_handlers()를 통해
            # Card의 infrastructure까지 import하는 체인을 타고 CardModel도 같은 Base에
            # 등록되기 때문에 별도로 Card 모델을 import할 필요 없이 이 한 번의 create_all로
            # cards 테이블도 생긴다.
            await conn.run_sync(Base.metadata.create_all)

        session_factory = async_sessionmaker(engine, expire_on_commit=False)

        async def override_get_session():
            async with session_factory() as session:
                yield session
                await session.commit()

        app.dependency_overrides[get_session] = override_get_session

        # main.py의 lifespan은 ASGITransport로 구동되는 이 클라이언트에서는 트리거되지
        # 않으므로, OutboxPoller/OutboxConsumer를 이 fixture가 직접 시작한다 — 이 테스트의
        # ephemeral Postgres/LocalStack을 가리키는 session_factory를 그대로 넘긴다.
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


# --- 동기 Adapter(ACL) 흐름: POST /cards가 Account BC를 즉시 조회한다 ---


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

    # AccountAdapter는 (account_id, owner_id) 쌍으로 조회하므로 다른 소유자에게는
    # "계좌 없음"과 동일하게 보인다 — Account BC의 find_accounts(account_id, owner_id, take=1)와 동일한 격리 방식.
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


# --- 비동기 Integration Event 흐름: Account 정지/해지가 OutboxPoller/OutboxConsumer를
# 거쳐 비동기로 Card에 전파된다 — 응답 직후에는 아직 전파되지 않았을 수 있으므로
# wait_until()로 폴링한다(conftest.py 참고). ---


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

    # account.suspended.v1 Integration Event가 OutboxPoller → SQS → OutboxConsumer를 거쳐
    # 비동기로 처리되어 카드 상태가 바뀐다 — 응답 직후 즉시 반영되어 있지 않을 수 있다.
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

    # 계좌 재개(reactivate)는 카드에 영향을 주는 Integration Event를 발행하지 않는다 —
    # 카드는 SUSPENDED 상태로 남아 있다(account.reactivated.v1 같은 이벤트 자체가 없다).
    # Account만 다시 ACTIVE가 되므로 곧바로 두 번째 suspend가 유효한 상태 전이가 된다.
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
