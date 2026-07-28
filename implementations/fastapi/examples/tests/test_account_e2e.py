import asyncio
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


async def create_account(client: AsyncClient, owner_id: str, currency: str, email: str = OWNER_EMAIL) -> dict:
    response = await client.post(
        "/accounts", json={"currency": currency, "email": email}, headers=auth_headers(owner_id)
    )
    assert response.status_code == 201
    return response.json()


@pytest.mark.asyncio
async def test_create_account_success(client: AsyncClient) -> None:
    response = await client.post(
        "/accounts", json={"currency": "KRW", "email": OWNER_EMAIL}, headers=auth_headers(OWNER_ID)
    )

    assert response.status_code == 201
    body = response.json()
    assert body["owner_id"] == OWNER_ID
    assert body["email"] == OWNER_EMAIL
    assert body["status"] == "ACTIVE"
    assert body["account_id"]
    assert body["created_at"]
    assert body["balance"] == {"amount": 0, "currency": "KRW"}


@pytest.mark.asyncio
async def test_create_account_missing_currency_returns_422(client: AsyncClient) -> None:
    response = await client.post("/accounts", json={"email": OWNER_EMAIL}, headers=auth_headers(OWNER_ID))
    assert response.status_code == 422
    body = response.json()
    assert body["statusCode"] == 422
    assert body["code"] == "VALIDATION_FAILED"
    assert body["error"] == "Unprocessable Entity"


@pytest.mark.asyncio
async def test_create_account_missing_email_returns_422(client: AsyncClient) -> None:
    response = await client.post("/accounts", json={"currency": "KRW"}, headers=auth_headers(OWNER_ID))
    assert response.status_code == 422


@pytest.mark.asyncio
async def test_create_account_invalid_email_returns_422(client: AsyncClient) -> None:
    response = await client.post(
        "/accounts", json={"currency": "KRW", "email": "not-an-email"}, headers=auth_headers(OWNER_ID)
    )
    assert response.status_code == 422


@pytest.mark.asyncio
async def test_deposit_success(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.post(
        f"/accounts/{account['account_id']}/deposit", json={"amount": 10000}, headers=auth_headers(OWNER_ID)
    )

    assert response.status_code == 201
    body = response.json()
    assert body["account_id"] == account["account_id"]
    assert body["type"] == "DEPOSIT"
    assert body["transaction_id"]


@pytest.mark.asyncio
async def test_deposit_account_not_found_returns_404(client: AsyncClient) -> None:
    response = await client.post(
        "/accounts/non-existent/deposit", json={"amount": 10000}, headers=auth_headers(OWNER_ID)
    )
    assert response.status_code == 404
    body = response.json()
    assert body["statusCode"] == 404
    assert body["code"] == "ACCOUNT_NOT_FOUND"
    assert body["error"] == "Not Found"


@pytest.mark.asyncio
async def test_deposit_other_owner_returns_404(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.post(
        f"/accounts/{account['account_id']}/deposit",
        json={"amount": 10000},
        headers=auth_headers(OTHER_OWNER_ID),
    )

    assert response.status_code == 404


@pytest.mark.asyncio
async def test_deposit_non_positive_amount_returns_400(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.post(
        f"/accounts/{account['account_id']}/deposit", json={"amount": 0}, headers=auth_headers(OWNER_ID)
    )

    assert response.status_code == 400
    body = response.json()
    assert body["statusCode"] == 400
    assert body["code"] == "INVALID_AMOUNT"
    assert body["error"] == "Bad Request"


@pytest.mark.asyncio
async def test_deposit_suspended_account_returns_400(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")
    await client.post(f"/accounts/{account['account_id']}/suspend", headers=auth_headers(OWNER_ID))

    response = await client.post(
        f"/accounts/{account['account_id']}/deposit", json={"amount": 10000}, headers=auth_headers(OWNER_ID)
    )

    assert response.status_code == 400


@pytest.mark.asyncio
async def test_withdraw_success(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")
    await client.post(
        f"/accounts/{account['account_id']}/deposit", json={"amount": 10000}, headers=auth_headers(OWNER_ID)
    )

    response = await client.post(
        f"/accounts/{account['account_id']}/withdraw", json={"amount": 4000}, headers=auth_headers(OWNER_ID)
    )

    assert response.status_code == 201
    assert response.json()["type"] == "WITHDRAWAL"


@pytest.mark.asyncio
async def test_withdraw_account_not_found_returns_404(client: AsyncClient) -> None:
    response = await client.post(
        "/accounts/non-existent/withdraw", json={"amount": 1000}, headers=auth_headers(OWNER_ID)
    )
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_withdraw_insufficient_balance_returns_400(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.post(
        f"/accounts/{account['account_id']}/withdraw", json={"amount": 1000}, headers=auth_headers(OWNER_ID)
    )

    assert response.status_code == 400


@pytest.mark.asyncio
async def test_withdraw_suspended_account_returns_400(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")
    await client.post(f"/accounts/{account['account_id']}/suspend", headers=auth_headers(OWNER_ID))

    response = await client.post(
        f"/accounts/{account['account_id']}/withdraw", json={"amount": 1000}, headers=auth_headers(OWNER_ID)
    )

    assert response.status_code == 400


@pytest.mark.asyncio
async def test_transfer_success(client: AsyncClient) -> None:
    source = await create_account(client, OWNER_ID, "KRW")
    await client.post(
        f"/accounts/{source['account_id']}/deposit", json={"amount": 10000}, headers=auth_headers(OWNER_ID)
    )
    target = await create_account(client, OTHER_OWNER_ID, "KRW", email="owner2@example.com")

    response = await client.post(
        f"/accounts/{source['account_id']}/transfer",
        json={"target_account_id": target["account_id"], "amount": 4000},
        headers=auth_headers(OWNER_ID),
    )

    assert response.status_code == 201
    body = response.json()
    assert body["transfer_id"]
    assert body["source_transaction"]["type"] == "WITHDRAWAL"
    assert body["target_transaction"]["type"] == "DEPOSIT"

    source_get = await client.get(f"/accounts/{source['account_id']}", headers=auth_headers(OWNER_ID))
    assert source_get.json()["balance"] == {"amount": 6000, "currency": "KRW"}

    target_get = await client.get(f"/accounts/{target['account_id']}", headers=auth_headers(OTHER_OWNER_ID))
    assert target_get.json()["balance"] == {"amount": 4000, "currency": "KRW"}


@pytest.mark.asyncio
async def test_transfer_to_other_owner_account_succeeds(client: AsyncClient) -> None:
    source = await create_account(client, OWNER_ID, "KRW")
    await client.post(
        f"/accounts/{source['account_id']}/deposit", json={"amount": 10000}, headers=auth_headers(OWNER_ID)
    )
    target = await create_account(client, OTHER_OWNER_ID, "KRW", email="owner2@example.com")

    response = await client.post(
        f"/accounts/{source['account_id']}/transfer",
        json={"target_account_id": target["account_id"], "amount": 1000},
        headers=auth_headers(OWNER_ID),
    )

    assert response.status_code == 201


@pytest.mark.asyncio
async def test_transfer_source_account_not_found_returns_404(client: AsyncClient) -> None:
    target = await create_account(client, OTHER_OWNER_ID, "KRW", email="owner2@example.com")

    response = await client.post(
        "/accounts/non-existent/transfer",
        json={"target_account_id": target["account_id"], "amount": 1000},
        headers=auth_headers(OWNER_ID),
    )

    assert response.status_code == 404
    assert response.json()["code"] == "ACCOUNT_NOT_FOUND"


@pytest.mark.asyncio
async def test_transfer_target_account_not_found_returns_404(client: AsyncClient) -> None:
    source = await create_account(client, OWNER_ID, "KRW")
    await client.post(
        f"/accounts/{source['account_id']}/deposit", json={"amount": 10000}, headers=auth_headers(OWNER_ID)
    )

    response = await client.post(
        f"/accounts/{source['account_id']}/transfer",
        json={"target_account_id": "non-existent", "amount": 1000},
        headers=auth_headers(OWNER_ID),
    )

    assert response.status_code == 404
    assert response.json()["code"] == "ACCOUNT_NOT_FOUND"


@pytest.mark.asyncio
async def test_transfer_same_account_returns_400(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")
    await client.post(
        f"/accounts/{account['account_id']}/deposit", json={"amount": 10000}, headers=auth_headers(OWNER_ID)
    )

    response = await client.post(
        f"/accounts/{account['account_id']}/transfer",
        json={"target_account_id": account["account_id"], "amount": 1000},
        headers=auth_headers(OWNER_ID),
    )

    assert response.status_code == 400
    assert response.json()["code"] == "TRANSFER_SAME_ACCOUNT"


@pytest.mark.asyncio
async def test_transfer_insufficient_balance_returns_400(client: AsyncClient) -> None:
    source = await create_account(client, OWNER_ID, "KRW")
    target = await create_account(client, OTHER_OWNER_ID, "KRW", email="owner2@example.com")

    response = await client.post(
        f"/accounts/{source['account_id']}/transfer",
        json={"target_account_id": target["account_id"], "amount": 1000},
        headers=auth_headers(OWNER_ID),
    )

    assert response.status_code == 400
    assert response.json()["code"] == "INSUFFICIENT_BALANCE"


@pytest.mark.asyncio
async def test_transfer_source_account_suspended_returns_400(client: AsyncClient) -> None:
    source = await create_account(client, OWNER_ID, "KRW")
    await client.post(
        f"/accounts/{source['account_id']}/deposit", json={"amount": 10000}, headers=auth_headers(OWNER_ID)
    )
    await client.post(f"/accounts/{source['account_id']}/suspend", headers=auth_headers(OWNER_ID))
    target = await create_account(client, OTHER_OWNER_ID, "KRW", email="owner2@example.com")

    response = await client.post(
        f"/accounts/{source['account_id']}/transfer",
        json={"target_account_id": target["account_id"], "amount": 1000},
        headers=auth_headers(OWNER_ID),
    )

    assert response.status_code == 400
    assert response.json()["code"] == "WITHDRAW_REQUIRES_ACTIVE_ACCOUNT"


@pytest.mark.asyncio
async def test_transfer_target_account_suspended_returns_400(client: AsyncClient) -> None:
    source = await create_account(client, OWNER_ID, "KRW")
    await client.post(
        f"/accounts/{source['account_id']}/deposit", json={"amount": 10000}, headers=auth_headers(OWNER_ID)
    )
    target = await create_account(client, OTHER_OWNER_ID, "KRW", email="owner2@example.com")
    await client.post(f"/accounts/{target['account_id']}/suspend", headers=auth_headers(OTHER_OWNER_ID))

    response = await client.post(
        f"/accounts/{source['account_id']}/transfer",
        json={"target_account_id": target["account_id"], "amount": 1000},
        headers=auth_headers(OWNER_ID),
    )

    assert response.status_code == 400
    assert response.json()["code"] == "DEPOSIT_REQUIRES_ACTIVE_ACCOUNT"


@pytest.mark.asyncio
async def test_transfer_currency_mismatch_returns_400(client: AsyncClient) -> None:
    source = await create_account(client, OWNER_ID, "KRW")
    await client.post(
        f"/accounts/{source['account_id']}/deposit", json={"amount": 10000}, headers=auth_headers(OWNER_ID)
    )
    target = await create_account(client, OTHER_OWNER_ID, "USD", email="owner2@example.com")

    response = await client.post(
        f"/accounts/{source['account_id']}/transfer",
        json={"target_account_id": target["account_id"], "amount": 1000},
        headers=auth_headers(OWNER_ID),
    )

    assert response.status_code == 400
    assert response.json()["code"] == "CURRENCY_MISMATCH"


@pytest.mark.asyncio
async def test_suspend_account_success(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.post(f"/accounts/{account['account_id']}/suspend", headers=auth_headers(OWNER_ID))
    assert response.status_code == 204

    get_response = await client.get(f"/accounts/{account['account_id']}", headers=auth_headers(OWNER_ID))
    assert get_response.json()["status"] == "SUSPENDED"


@pytest.mark.asyncio
async def test_suspend_account_not_found_returns_404(client: AsyncClient) -> None:
    response = await client.post("/accounts/non-existent/suspend", headers=auth_headers(OWNER_ID))
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_suspend_already_suspended_returns_400(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")
    await client.post(f"/accounts/{account['account_id']}/suspend", headers=auth_headers(OWNER_ID))

    response = await client.post(f"/accounts/{account['account_id']}/suspend", headers=auth_headers(OWNER_ID))

    assert response.status_code == 400


@pytest.mark.asyncio
async def test_reactivate_account_success(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")
    await client.post(f"/accounts/{account['account_id']}/suspend", headers=auth_headers(OWNER_ID))

    response = await client.post(f"/accounts/{account['account_id']}/reactivate", headers=auth_headers(OWNER_ID))
    assert response.status_code == 204

    get_response = await client.get(f"/accounts/{account['account_id']}", headers=auth_headers(OWNER_ID))
    assert get_response.json()["status"] == "ACTIVE"


@pytest.mark.asyncio
async def test_reactivate_account_not_found_returns_404(client: AsyncClient) -> None:
    response = await client.post("/accounts/non-existent/reactivate", headers=auth_headers(OWNER_ID))
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_reactivate_active_account_returns_400(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.post(f"/accounts/{account['account_id']}/reactivate", headers=auth_headers(OWNER_ID))

    assert response.status_code == 400


@pytest.mark.asyncio
async def test_close_account_success(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.post(f"/accounts/{account['account_id']}/close", headers=auth_headers(OWNER_ID))
    assert response.status_code == 204

    get_response = await client.get(f"/accounts/{account['account_id']}", headers=auth_headers(OWNER_ID))
    assert get_response.json()["status"] == "CLOSED"


@pytest.mark.asyncio
async def test_close_account_not_found_returns_404(client: AsyncClient) -> None:
    response = await client.post("/accounts/non-existent/close", headers=auth_headers(OWNER_ID))
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_close_account_balance_not_zero_returns_400(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")
    await client.post(
        f"/accounts/{account['account_id']}/deposit", json={"amount": 5000}, headers=auth_headers(OWNER_ID)
    )

    response = await client.post(f"/accounts/{account['account_id']}/close", headers=auth_headers(OWNER_ID))

    assert response.status_code == 400


@pytest.mark.asyncio
async def test_close_already_closed_returns_400(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")
    await client.post(f"/accounts/{account['account_id']}/close", headers=auth_headers(OWNER_ID))

    response = await client.post(f"/accounts/{account['account_id']}/close", headers=auth_headers(OWNER_ID))

    assert response.status_code == 400


@pytest.mark.asyncio
async def test_get_account_success(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.get(f"/accounts/{account['account_id']}", headers=auth_headers(OWNER_ID))

    assert response.status_code == 200
    body = response.json()
    assert body["account_id"] == account["account_id"]
    assert body["owner_id"] == OWNER_ID
    assert body["updated_at"]


@pytest.mark.asyncio
async def test_get_account_not_found_returns_404(client: AsyncClient) -> None:
    response = await client.get("/accounts/non-existent", headers=auth_headers(OWNER_ID))
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_get_account_other_owner_returns_404(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.get(f"/accounts/{account['account_id']}", headers=auth_headers(OTHER_OWNER_ID))

    assert response.status_code == 404


@pytest.mark.asyncio
async def test_get_transactions_with_pagination(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")
    await client.post(
        f"/accounts/{account['account_id']}/deposit", json={"amount": 10000}, headers=auth_headers(OWNER_ID)
    )
    await client.post(
        f"/accounts/{account['account_id']}/withdraw", json={"amount": 3000}, headers=auth_headers(OWNER_ID)
    )

    response = await client.get(
        f"/accounts/{account['account_id']}/transactions",
        params={"page": 0, "take": 20},
        headers=auth_headers(OWNER_ID),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["count"] == 2
    assert len(body["transactions"]) == 2


@pytest.mark.asyncio
async def test_get_transactions_account_not_found_returns_404(client: AsyncClient) -> None:
    response = await client.get("/accounts/non-existent/transactions", headers=auth_headers(OWNER_ID))
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_get_transactions_beyond_last_page_returns_empty(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.get(
        f"/accounts/{account['account_id']}/transactions",
        params={"page": 5, "take": 20},
        headers=auth_headers(OWNER_ID),
    )

    assert response.status_code == 200
    assert response.json()["count"] == 0


# No real Ollama in this test environment, so both LLM calls (NlTransactionQueryTranslatorImpl/
# NlTransactionAnswerComposerImpl) fall back to their non-blocking defaults — these tests assert
# only on response shape/count, not exact wording (see docs/architecture/layer-architecture.md).
@pytest.mark.asyncio
async def test_ask_transaction_history_returns_200_with_an_answer_grounded_in_the_requesters_own_transactions(
    client: AsyncClient,
) -> None:
    account = await create_account(client, OWNER_ID, "KRW")
    await client.post(
        f"/accounts/{account['account_id']}/deposit", json={"amount": 10000}, headers=auth_headers(OWNER_ID)
    )

    response = await client.post(
        f"/accounts/{account['account_id']}/transactions/ask",
        json={"question": "How much have I deposited?"},
        headers=auth_headers(OWNER_ID),
    )

    assert response.status_code == 200
    body = response.json()
    assert isinstance(body["answer"], str)
    assert len(body["answer"]) > 0
    assert body["matched_count"] == 1


@pytest.mark.asyncio
async def test_ask_transaction_history_with_an_empty_question_returns_422(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.post(
        f"/accounts/{account['account_id']}/transactions/ask",
        json={"question": ""},
        headers=auth_headers(OWNER_ID),
    )

    assert response.status_code == 422


@pytest.mark.asyncio
async def test_ask_transaction_history_account_not_found_returns_404(client: AsyncClient) -> None:
    response = await client.post(
        "/accounts/non-existent/transactions/ask",
        json={"question": "anything"},
        headers=auth_headers(OWNER_ID),
    )

    assert response.status_code == 404


@pytest.mark.asyncio
async def test_ask_transaction_history_a_different_owner_returns_404(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID, "KRW")

    response = await client.post(
        f"/accounts/{account['account_id']}/transactions/ask",
        json={"question": "anything"},
        headers=auth_headers(OTHER_OWNER_ID),
    )

    assert response.status_code == 404


# Transaction auto-categorization (merchant_name -> category)
#
# The LLM behind TransactionAutoCategorizer isn't available in this e2e environment either
# (same reasoning as the /transactions/ask tests above), so the categorization call falls back
# to OTHER — but this still exercises the real async pipeline end to end: the Domain Event ->
# Outbox -> SQS -> OutboxConsumer -> both MoneyWithdrawn subscribers (notification +
# CategorizeTransactionEventHandler) -> the repository write all actually run, only the LLM
# call itself degrades to its non-blocking default. Uses its own fixture (unlike `client`
# above) since it needs the outbox/SQS pipeline actually running.
@pytest_asyncio.fixture(scope="module")
async def categorization_env() -> AsyncGenerator[dict, None]:
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
            await conn.run_sync(Base.metadata.create_all)

        session_factory = async_sessionmaker(engine, expire_on_commit=False)

        async def override_get_session():
            async with session_factory() as session:
                yield session
                await session.commit()

        app.dependency_overrides[get_session] = override_get_session

        # OutboxPoller/OutboxConsumer delivers MoneyWithdrawn to both of its subscribers
        # asynchronously via SQS — main.py's lifespan isn't triggered under ASGITransport, so
        # this fixture starts it in the background directly (same pattern as
        # test_notification_e2e.py's notification_env).
        outbox_tasks = start_outbox_background_tasks(session_factory)

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as ac:
            yield {"client": ac, "session_factory": session_factory}

        await stop_outbox_background_tasks(outbox_tasks)
        await engine.dispose()
        app.dependency_overrides.pop(get_session, None)

        for key, value in previous_env.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


@pytest.mark.asyncio
async def test_withdraw_with_a_merchant_name_then_the_transaction_is_asynchronously_categorized(
    categorization_env: dict,
) -> None:
    client: AsyncClient = categorization_env["client"]
    account = await create_account(client, OWNER_ID, "KRW")
    await client.post(
        f"/accounts/{account['account_id']}/deposit", json={"amount": 10000}, headers=auth_headers(OWNER_ID)
    )
    await client.post(
        f"/accounts/{account['account_id']}/withdraw",
        json={"amount": 5500, "merchant_name": "Starbucks Gangnam"},
        headers=auth_headers(OWNER_ID),
    )

    categorized: dict = {}

    async def _categorized() -> bool:
        response = await client.get(
            f"/accounts/{account['account_id']}/transactions",
            params={"type": "WITHDRAWAL"},
            headers=auth_headers(OWNER_ID),
        )
        transactions = response.json()["transactions"]
        if transactions and transactions[0].get("category"):
            categorized["transaction"] = transactions[0]
            return True
        return False

    await wait_until(_categorized)

    assert categorized["transaction"]["merchant_name"] == "Starbucks Gangnam"
    assert categorized["transaction"]["category"] == "OTHER"


@pytest.mark.asyncio
async def test_withdraw_without_a_merchant_name_then_the_transaction_is_never_categorized(
    categorization_env: dict,
) -> None:
    client: AsyncClient = categorization_env["client"]
    account = await create_account(client, OWNER_ID, "KRW")
    await client.post(
        f"/accounts/{account['account_id']}/deposit", json={"amount": 10000}, headers=auth_headers(OWNER_ID)
    )
    await client.post(
        f"/accounts/{account['account_id']}/withdraw", json={"amount": 5500}, headers=auth_headers(OWNER_ID)
    )

    # No merchant_name to react to, so there's nothing to wait out — give the (skipped)
    # reaction the same window as the happy path would need, then assert it never ran.
    await asyncio.sleep(5)
    response = await client.get(
        f"/accounts/{account['account_id']}/transactions",
        params={"type": "WITHDRAWAL"},
        headers=auth_headers(OWNER_ID),
    )
    transaction = response.json()["transactions"][0]

    assert transaction.get("merchant_name") is None
    assert transaction.get("category") is None
