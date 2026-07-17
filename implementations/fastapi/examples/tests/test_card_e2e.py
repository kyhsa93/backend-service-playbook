from collections.abc import AsyncGenerator

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from main import app
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
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
    with PostgresContainer("postgres:16-alpine") as postgres:
        url = postgres.get_connection_url().replace("postgresql+psycopg2", "postgresql+asyncpg")
        engine = create_async_engine(url)

        async with engine.begin() as conn:
            # Base.metadata에는 accounts/cards/outbox/... 가 모두 등록되어 있다 — main.py가
            # account_router를 import하고, account_router가 (Outbox 배선을 위해) Card의
            # infrastructure까지 import하는 체인을 타고 CardModel도 같은 Base에 등록되기 때문에
            # 별도로 Card 모델을 import할 필요 없이 이 한 번의 create_all로 cards 테이블도 생긴다.
            await conn.run_sync(Base.metadata.create_all)

        session_factory = async_sessionmaker(engine, expire_on_commit=False)

        async def override_get_session():
            async with session_factory() as session:
                yield session
                await session.commit()

        app.dependency_overrides[get_session] = override_get_session

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as ac:
            yield ac

        await engine.dispose()
        app.dependency_overrides.pop(get_session, None)


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
    # "계좌 없음"과 동일하게 보인다 — Account BC의 find_by_id와 동일한 격리 방식.
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


# --- 비동기 Integration Event 흐름: Account 정지/해지가 Outbox 드레인을 통해 Card에 전파된다 ---


@pytest.mark.asyncio
async def test_suspending_account_cascades_to_suspend_its_active_cards(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID)
    card = await issue_card(client, OWNER_ID, account["account_id"])
    assert card["status"] == "ACTIVE"

    suspend_response = await client.post(f"/accounts/{account['account_id']}/suspend", headers=auth_headers(OWNER_ID))
    assert suspend_response.status_code == 204

    # account.suspended.v1 Integration Event가 같은 요청의 Outbox 드레인 안에서 처리되어
    # 카드 상태가 즉시 바뀐다 — 별도의 폴링 대기가 필요 없다.
    get_response = await client.get(f"/cards/{card['card_id']}", headers=auth_headers(OWNER_ID))
    assert get_response.json()["status"] == "SUSPENDED"


@pytest.mark.asyncio
async def test_suspending_account_does_not_affect_cards_of_other_accounts(client: AsyncClient) -> None:
    account_a = await create_account(client, OWNER_ID)
    account_b = await create_account(client, OWNER_ID)
    card_a = await issue_card(client, OWNER_ID, account_a["account_id"])
    card_b = await issue_card(client, OWNER_ID, account_b["account_id"])

    await client.post(f"/accounts/{account_a['account_id']}/suspend", headers=auth_headers(OWNER_ID))

    card_a_after = await client.get(f"/cards/{card_a['card_id']}", headers=auth_headers(OWNER_ID))
    card_b_after = await client.get(f"/cards/{card_b['card_id']}", headers=auth_headers(OWNER_ID))
    assert card_a_after.json()["status"] == "SUSPENDED"
    assert card_b_after.json()["status"] == "ACTIVE"


@pytest.mark.asyncio
async def test_closing_account_cascades_to_cancel_its_cards(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID)
    card = await issue_card(client, OWNER_ID, account["account_id"])

    close_response = await client.post(f"/accounts/{account['account_id']}/close", headers=auth_headers(OWNER_ID))
    assert close_response.status_code == 204

    get_response = await client.get(f"/cards/{card['card_id']}", headers=auth_headers(OWNER_ID))
    assert get_response.json()["status"] == "CANCELLED"


@pytest.mark.asyncio
async def test_closing_account_cancels_already_suspended_cards_too(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID)
    card = await issue_card(client, OWNER_ID, account["account_id"])
    await client.post(f"/accounts/{account['account_id']}/suspend", headers=auth_headers(OWNER_ID))
    assert (await client.get(f"/cards/{card['card_id']}", headers=auth_headers(OWNER_ID))).json()["status"] == (
        "SUSPENDED"
    )

    await client.post(f"/accounts/{account['account_id']}/reactivate", headers=auth_headers(OWNER_ID))
    await client.post(f"/accounts/{account['account_id']}/suspend", headers=auth_headers(OWNER_ID))

    close_response = await client.post(f"/accounts/{account['account_id']}/close", headers=auth_headers(OWNER_ID))
    assert close_response.status_code == 204

    get_response = await client.get(f"/cards/{card['card_id']}", headers=auth_headers(OWNER_ID))
    assert get_response.json()["status"] == "CANCELLED"


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
