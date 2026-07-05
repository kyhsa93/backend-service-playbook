from collections.abc import AsyncGenerator

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from testcontainers.postgres import PostgresContainer

from main import app
from src.account.infrastructure.persistence.account_repository import Base
from src.database import get_session

OWNER_ID = "owner-1"
OTHER_OWNER_ID = "owner-2"


@pytest_asyncio.fixture(scope="session")
async def client() -> AsyncGenerator[AsyncClient, None]:
    with PostgresContainer("postgres:16-alpine") as postgres:
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
        async with AsyncClient(transport=transport, base_url="http://test") as ac:
            yield ac

        await engine.dispose()


async def create_account(client: AsyncClient, owner_id: str, currency: str) -> dict:
    response = await client.post("/accounts", json={"currency": currency}, headers={"X-User-Id": owner_id})
    assert response.status_code == 201
    return response.json()


@pytest.mark.asyncio
async def test_create_account_success(client: AsyncClient) -> None:
    response = await client.post("/accounts", json={"currency": "KRW"}, headers={"X-User-Id": OWNER_ID})

    assert response.status_code == 201
    body = response.json()
    assert body["owner_id"] == OWNER_ID
    assert body["status"] == "ACTIVE"
    assert body["account_id"]
    assert body["created_at"]
    assert body["balance"] == {"amount": 0, "currency": "KRW"}


@pytest.mark.asyncio
async def test_create_account_missing_currency_returns_422(client: AsyncClient) -> None:
    response = await client.post("/accounts", json={}, headers={"X-User-Id": OWNER_ID})
    assert response.status_code == 422


@pytest.mark.asyncio
async def test_deposit_success(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.post(
        f"/accounts/{account['account_id']}/deposit", json={"amount": 10000}, headers={"X-User-Id": OWNER_ID}
    )

    assert response.status_code == 201
    body = response.json()
    assert body["account_id"] == account["account_id"]
    assert body["type"] == "DEPOSIT"
    assert body["transaction_id"]


@pytest.mark.asyncio
async def test_deposit_account_not_found_returns_404(client: AsyncClient) -> None:
    response = await client.post(
        "/accounts/non-existent/deposit", json={"amount": 10000}, headers={"X-User-Id": OWNER_ID}
    )
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_deposit_other_owner_returns_404(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.post(
        f"/accounts/{account['account_id']}/deposit",
        json={"amount": 10000},
        headers={"X-User-Id": OTHER_OWNER_ID},
    )

    assert response.status_code == 404


@pytest.mark.asyncio
async def test_deposit_non_positive_amount_returns_400(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.post(
        f"/accounts/{account['account_id']}/deposit", json={"amount": 0}, headers={"X-User-Id": OWNER_ID}
    )

    assert response.status_code == 400


@pytest.mark.asyncio
async def test_deposit_suspended_account_returns_400(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")
    await client.post(f"/accounts/{account['account_id']}/suspend", headers={"X-User-Id": OWNER_ID})

    response = await client.post(
        f"/accounts/{account['account_id']}/deposit", json={"amount": 10000}, headers={"X-User-Id": OWNER_ID}
    )

    assert response.status_code == 400


@pytest.mark.asyncio
async def test_withdraw_success(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")
    await client.post(
        f"/accounts/{account['account_id']}/deposit", json={"amount": 10000}, headers={"X-User-Id": OWNER_ID}
    )

    response = await client.post(
        f"/accounts/{account['account_id']}/withdraw", json={"amount": 4000}, headers={"X-User-Id": OWNER_ID}
    )

    assert response.status_code == 201
    assert response.json()["type"] == "WITHDRAWAL"


@pytest.mark.asyncio
async def test_withdraw_account_not_found_returns_404(client: AsyncClient) -> None:
    response = await client.post(
        "/accounts/non-existent/withdraw", json={"amount": 1000}, headers={"X-User-Id": OWNER_ID}
    )
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_withdraw_insufficient_balance_returns_400(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.post(
        f"/accounts/{account['account_id']}/withdraw", json={"amount": 1000}, headers={"X-User-Id": OWNER_ID}
    )

    assert response.status_code == 400


@pytest.mark.asyncio
async def test_withdraw_suspended_account_returns_400(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")
    await client.post(f"/accounts/{account['account_id']}/suspend", headers={"X-User-Id": OWNER_ID})

    response = await client.post(
        f"/accounts/{account['account_id']}/withdraw", json={"amount": 1000}, headers={"X-User-Id": OWNER_ID}
    )

    assert response.status_code == 400


@pytest.mark.asyncio
async def test_suspend_account_success(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.post(f"/accounts/{account['account_id']}/suspend", headers={"X-User-Id": OWNER_ID})
    assert response.status_code == 204

    get_response = await client.get(f"/accounts/{account['account_id']}", headers={"X-User-Id": OWNER_ID})
    assert get_response.json()["status"] == "SUSPENDED"


@pytest.mark.asyncio
async def test_suspend_account_not_found_returns_404(client: AsyncClient) -> None:
    response = await client.post("/accounts/non-existent/suspend", headers={"X-User-Id": OWNER_ID})
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_suspend_already_suspended_returns_400(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")
    await client.post(f"/accounts/{account['account_id']}/suspend", headers={"X-User-Id": OWNER_ID})

    response = await client.post(f"/accounts/{account['account_id']}/suspend", headers={"X-User-Id": OWNER_ID})

    assert response.status_code == 400


@pytest.mark.asyncio
async def test_reactivate_account_success(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")
    await client.post(f"/accounts/{account['account_id']}/suspend", headers={"X-User-Id": OWNER_ID})

    response = await client.post(f"/accounts/{account['account_id']}/reactivate", headers={"X-User-Id": OWNER_ID})
    assert response.status_code == 204

    get_response = await client.get(f"/accounts/{account['account_id']}", headers={"X-User-Id": OWNER_ID})
    assert get_response.json()["status"] == "ACTIVE"


@pytest.mark.asyncio
async def test_reactivate_account_not_found_returns_404(client: AsyncClient) -> None:
    response = await client.post("/accounts/non-existent/reactivate", headers={"X-User-Id": OWNER_ID})
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_reactivate_active_account_returns_400(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.post(f"/accounts/{account['account_id']}/reactivate", headers={"X-User-Id": OWNER_ID})

    assert response.status_code == 400


@pytest.mark.asyncio
async def test_close_account_success(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.post(f"/accounts/{account['account_id']}/close", headers={"X-User-Id": OWNER_ID})
    assert response.status_code == 204

    get_response = await client.get(f"/accounts/{account['account_id']}", headers={"X-User-Id": OWNER_ID})
    assert get_response.json()["status"] == "CLOSED"


@pytest.mark.asyncio
async def test_close_account_not_found_returns_404(client: AsyncClient) -> None:
    response = await client.post("/accounts/non-existent/close", headers={"X-User-Id": OWNER_ID})
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_close_account_balance_not_zero_returns_400(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")
    await client.post(
        f"/accounts/{account['account_id']}/deposit", json={"amount": 5000}, headers={"X-User-Id": OWNER_ID}
    )

    response = await client.post(f"/accounts/{account['account_id']}/close", headers={"X-User-Id": OWNER_ID})

    assert response.status_code == 400


@pytest.mark.asyncio
async def test_close_already_closed_returns_400(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")
    await client.post(f"/accounts/{account['account_id']}/close", headers={"X-User-Id": OWNER_ID})

    response = await client.post(f"/accounts/{account['account_id']}/close", headers={"X-User-Id": OWNER_ID})

    assert response.status_code == 400


@pytest.mark.asyncio
async def test_get_account_success(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.get(f"/accounts/{account['account_id']}", headers={"X-User-Id": OWNER_ID})

    assert response.status_code == 200
    body = response.json()
    assert body["account_id"] == account["account_id"]
    assert body["owner_id"] == OWNER_ID
    assert body["updated_at"]


@pytest.mark.asyncio
async def test_get_account_not_found_returns_404(client: AsyncClient) -> None:
    response = await client.get("/accounts/non-existent", headers={"X-User-Id": OWNER_ID})
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_get_account_other_owner_returns_404(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.get(f"/accounts/{account['account_id']}", headers={"X-User-Id": OTHER_OWNER_ID})

    assert response.status_code == 404


@pytest.mark.asyncio
async def test_get_transactions_with_pagination(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")
    await client.post(
        f"/accounts/{account['account_id']}/deposit", json={"amount": 10000}, headers={"X-User-Id": OWNER_ID}
    )
    await client.post(
        f"/accounts/{account['account_id']}/withdraw", json={"amount": 3000}, headers={"X-User-Id": OWNER_ID}
    )

    response = await client.get(
        f"/accounts/{account['account_id']}/transactions",
        params={"page": 0, "take": 20},
        headers={"X-User-Id": OWNER_ID},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["count"] == 2
    assert len(body["transactions"]) == 2


@pytest.mark.asyncio
async def test_get_transactions_account_not_found_returns_404(client: AsyncClient) -> None:
    response = await client.get("/accounts/non-existent/transactions", headers={"X-User-Id": OWNER_ID})
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_get_transactions_beyond_last_page_returns_empty(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.get(
        f"/accounts/{account['account_id']}/transactions",
        params={"page": 5, "take": 20},
        headers={"X-User-Id": OWNER_ID},
    )

    assert response.status_code == 200
    assert response.json()["count"] == 0
