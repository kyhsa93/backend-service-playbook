import os
import uuid
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

# Just like the other e2e test files (test_account_e2e.py/test_card_e2e.py), this file also
# starts its own separate Postgres/LocalStack testcontainer with its own session-scope
# `client` fixture — they aren't shared across files, so they don't contaminate each other
# (contamination between multiple test functions within a file is avoided by each test
# creating its own fresh account/card).
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
            await conn.run_sync(Base.metadata.create_all)

        session_factory = async_sessionmaker(engine, expire_on_commit=False)

        async def override_get_session():
            async with session_factory() as session:
                yield session
                await session.commit()

        app.dependency_overrides[get_session] = override_get_session

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


async def create_account(client: AsyncClient, owner_id: str, currency: str = "KRW") -> dict:
    response = await client.post(
        "/accounts", json={"currency": currency, "email": OWNER_EMAIL}, headers=auth_headers(owner_id)
    )
    assert response.status_code == 201
    return response.json()


async def issue_card(client: AsyncClient, owner_id: str, account_id: str, brand: str = "VISA") -> dict:
    response = await client.post(
        "/cards", json={"account_id": account_id, "brand": brand}, headers=auth_headers(owner_id)
    )
    assert response.status_code == 201
    return response.json()


async def deposit(client: AsyncClient, owner_id: str, account_id: str, amount: int) -> dict:
    response = await client.post(
        f"/accounts/{account_id}/deposit", json={"amount": amount}, headers=auth_headers(owner_id)
    )
    assert response.status_code == 201
    return response.json()


async def get_account(client: AsyncClient, owner_id: str, account_id: str) -> dict:
    response = await client.get(f"/accounts/{account_id}", headers=auth_headers(owner_id))
    assert response.status_code == 200
    return response.json()


async def _balance_is(client: AsyncClient, owner_id: str, account_id: str, expected: int) -> bool:
    account = await get_account(client, owner_id, account_id)
    return account["balance"]["amount"] == expected


async def create_payment(client: AsyncClient, owner_id: str, card_id: str, amount: int) -> dict:
    response = await client.post(
        "/payments", json={"card_id": card_id, "amount": amount}, headers=auth_headers(owner_id)
    )
    assert response.status_code == 201
    return response.json()


async def make_funded_card(client: AsyncClient, owner_id: str, balance: int = 100000) -> tuple[dict, dict]:
    account = await create_account(client, owner_id)
    card = await issue_card(client, owner_id, account["account_id"])
    await deposit(client, owner_id, account["account_id"], balance)
    return account, card


# --- Synchronous Adapter (ACL) flow: POST /payments immediately queries the Card/Account BCs to
# decide whether payment is allowed ---


@pytest.mark.asyncio
async def test_create_payment_success_debits_account_immediately(client: AsyncClient) -> None:
    account, card = await make_funded_card(client, OWNER_ID, balance=100000)

    payment = await create_payment(client, OWNER_ID, card["card_id"], 30000)

    assert payment["status"] == "COMPLETED"
    assert payment["account_id"] == account["account_id"]
    assert payment["amount"] == 30000

    # payment.completed.v1 is processed asynchronously through OutboxPoller → SQS →
    # OutboxConsumer, and the balance is reflected — it may not be reflected immediately
    # right after the response, so this polls (the same pattern as account.suspended.v1 in
    # test_card_e2e.py).
    await wait_until(lambda: _balance_is(client, OWNER_ID, account["account_id"], 70000))


@pytest.mark.asyncio
async def test_create_payment_inactive_card_returns_400(client: AsyncClient) -> None:
    account, card = await make_funded_card(client, OWNER_ID, balance=100000)
    # Once the account is suspended, the Card BC subscribes to account.suspended.v1 and the card
    # is suspended asynchronously too.
    suspend_response = await client.post(f"/accounts/{account['account_id']}/suspend", headers=auth_headers(OWNER_ID))
    assert suspend_response.status_code == 204

    async def _card_is_inactive() -> bool:
        response = await client.post(
            "/payments", json={"card_id": card["card_id"], "amount": 1000}, headers=auth_headers(OWNER_ID)
        )
        return response.status_code == 400 and response.json()["code"] == "PAYMENT_REQUIRES_ACTIVE_CARD"

    await wait_until(_card_is_inactive)


@pytest.mark.asyncio
async def test_create_payment_insufficient_balance_returns_400(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID)
    card = await issue_card(client, OWNER_ID, account["account_id"])  # zero balance

    response = await client.post(
        "/payments", json={"card_id": card["card_id"], "amount": 1000}, headers=auth_headers(OWNER_ID)
    )

    assert response.status_code == 400
    body = response.json()
    assert body["code"] == "INSUFFICIENT_BALANCE"


@pytest.mark.asyncio
async def test_create_payment_linked_card_not_found_returns_404(client: AsyncClient) -> None:
    response = await client.post(
        "/payments", json={"card_id": "non-existent", "amount": 1000}, headers=auth_headers(OWNER_ID)
    )

    assert response.status_code == 404
    body = response.json()
    assert body["code"] == "LINKED_CARD_NOT_FOUND"


@pytest.mark.asyncio
async def test_create_payment_other_owners_card_returns_404(client: AsyncClient) -> None:
    _, card = await make_funded_card(client, OWNER_ID, balance=100000)

    response = await client.post(
        "/payments", json={"card_id": card["card_id"], "amount": 1000}, headers=auth_headers(OTHER_OWNER_ID)
    )

    assert response.status_code == 404


# --- Async Integration Event flow: cancelling a payment triggers a compensating credit ---


@pytest.mark.asyncio
async def test_cancel_payment_restores_balance_via_compensating_credit(client: AsyncClient) -> None:
    account, card = await make_funded_card(client, OWNER_ID, balance=100000)
    payment = await create_payment(client, OWNER_ID, card["card_id"], 30000)
    # Wait until payment.completed.v1 is processed asynchronously and the balance is reflected.
    await wait_until(lambda: _balance_is(client, OWNER_ID, account["account_id"], 70000))

    cancel_response = await client.post(
        f"/payments/{payment['payment_id']}/cancel", json={"reason": "customer request"}, headers=auth_headers(OWNER_ID)
    )
    assert cancel_response.status_code == 204

    # payment.cancelled.v1 is processed asynchronously through OutboxPoller → SQS →
    # OutboxConsumer, and the balance is restored via a compensating credit.
    await wait_until(lambda: _balance_is(client, OWNER_ID, account["account_id"], 100000))

    get_payment_response = await client.get(f"/payments/{payment['payment_id']}", headers=auth_headers(OWNER_ID))
    assert get_payment_response.json()["status"] == "CANCELLED"


@pytest.mark.asyncio
async def test_cancel_pending_or_already_cancelled_payment_returns_400(client: AsyncClient) -> None:
    account, card = await make_funded_card(client, OWNER_ID, balance=100000)
    payment = await create_payment(client, OWNER_ID, card["card_id"], 10000)
    await client.post(
        f"/payments/{payment['payment_id']}/cancel",
        json={"reason": "first cancellation"},
        headers=auth_headers(OWNER_ID),
    )

    response = await client.post(
        f"/payments/{payment['payment_id']}/cancel",
        json={"reason": "second cancellation"},
        headers=auth_headers(OWNER_ID),
    )

    assert response.status_code == 400
    assert response.json()["code"] == "PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT"


# --- Verifying RefundEligibilityService (Domain Service) decisions ---


@pytest.mark.asyncio
async def test_refund_within_payment_amount_is_approved_and_credits_account(client: AsyncClient) -> None:
    account, card = await make_funded_card(client, OWNER_ID, balance=100000)
    payment = await create_payment(client, OWNER_ID, card["card_id"], 30000)
    await wait_until(lambda: _balance_is(client, OWNER_ID, account["account_id"], 70000))

    response = await client.post(
        f"/payments/{payment['payment_id']}/refunds",
        json={"amount": 30000, "reason": "personal change of mind"},
        headers=auth_headers(OWNER_ID),
    )

    assert response.status_code == 201
    body = response.json()
    assert body["status"] == "APPROVED"
    assert body["decision_note"] == "The refund has been approved."

    # refund.approved.v1 is processed asynchronously through OutboxPoller → SQS →
    # OutboxConsumer, and the refund credit is applied.
    await wait_until(lambda: _balance_is(client, OWNER_ID, account["account_id"], 100000))


@pytest.mark.asyncio
async def test_refund_exceeding_payment_amount_is_rejected_with_201(client: AsyncClient) -> None:
    account, card = await make_funded_card(client, OWNER_ID, balance=100000)
    payment = await create_payment(client, OWNER_ID, card["card_id"], 30000)

    response = await client.post(
        f"/payments/{payment['payment_id']}/refunds",
        json={"amount": 30001, "reason": "personal change of mind"},
        headers=auth_headers(OWNER_ID),
    )

    # A refund rejection is a valid domain decision outcome — it responds with 201 + status: REJECTED, not a 4xx error.
    assert response.status_code == 201
    body = response.json()
    assert body["status"] == "REJECTED"
    assert body["decision_note"] == "The refund amount cannot exceed the payment amount."

    # A rejected refund has no Domain Event, so it doesn't affect the balance at all —
    # however, since the payment's own debit (payment.completed.v1) is async, this waits
    # until it's reflected as 70000.
    await wait_until(lambda: _balance_is(client, OWNER_ID, account["account_id"], 70000))


@pytest.mark.asyncio
async def test_refund_on_non_completed_payment_is_rejected_with_201(client: AsyncClient) -> None:
    account, card = await make_funded_card(client, OWNER_ID, balance=100000)
    payment = await create_payment(client, OWNER_ID, card["card_id"], 30000)
    await client.post(
        f"/payments/{payment['payment_id']}/cancel", json={"reason": "customer request"}, headers=auth_headers(OWNER_ID)
    )

    response = await client.post(
        f"/payments/{payment['payment_id']}/refunds",
        json={"amount": 30000, "reason": "personal change of mind"},
        headers=auth_headers(OWNER_ID),
    )

    assert response.status_code == 201
    body = response.json()
    assert body["status"] == "REJECTED"
    assert body["decision_note"] == "A refund can only be requested for a completed payment."


@pytest.mark.asyncio
async def test_request_refund_payment_not_found_returns_404(client: AsyncClient) -> None:
    response = await client.post(
        "/payments/non-existent/refunds",
        json={"amount": 1000, "reason": "personal change of mind"},
        headers=auth_headers(OWNER_ID),
    )

    assert response.status_code == 404
    assert response.json()["code"] == "PAYMENT_NOT_FOUND"


# --- Lookup (Query) — scoped only to the authenticated requester's own records ---


@pytest.mark.asyncio
async def test_get_payments_lists_only_requesters_own_payments(client: AsyncClient) -> None:
    # The client fixture shares the same Postgres across the whole session (including other
    # e2e test files), so this test, which verifies an exact count, doesn't reuse the shared
    # OWNER_ID/OTHER_OWNER_ID and instead uses an owner id unique to this test — so it doesn't
    # mix with payment data created by other tests.
    owner_id = f"owner-payments-list-{uuid.uuid4().hex}"
    other_owner_id = f"owner-payments-list-other-{uuid.uuid4().hex}"
    _, card_a = await make_funded_card(client, owner_id, balance=100000)
    await create_payment(client, owner_id, card_a["card_id"], 1000)
    await create_payment(client, owner_id, card_a["card_id"], 2000)
    _, card_b = await make_funded_card(client, other_owner_id, balance=100000)
    await create_payment(client, other_owner_id, card_b["card_id"], 3000)

    response = await client.get("/payments", headers=auth_headers(owner_id))

    assert response.status_code == 200
    body = response.json()
    assert body["count"] == 2
    assert all(p["owner_id"] == owner_id for p in body["payments"])


@pytest.mark.asyncio
async def test_get_refunds_lists_refunds_for_a_payment(client: AsyncClient) -> None:
    account, card = await make_funded_card(client, OWNER_ID, balance=100000)
    payment = await create_payment(client, OWNER_ID, card["card_id"], 30000)
    await client.post(
        f"/payments/{payment['payment_id']}/refunds",
        json={"amount": 30001, "reason": "excessive refund attempt"},
        headers=auth_headers(OWNER_ID),
    )

    response = await client.get(f"/payments/{payment['payment_id']}/refunds", headers=auth_headers(OWNER_ID))

    assert response.status_code == 200
    body = response.json()
    assert body["count"] == 1
    assert body["refunds"][0]["status"] == "REJECTED"


@pytest.mark.asyncio
async def test_get_refunds_other_owners_payment_returns_404(client: AsyncClient) -> None:
    _, card = await make_funded_card(client, OWNER_ID, balance=100000)
    payment = await create_payment(client, OWNER_ID, card["card_id"], 1000)

    response = await client.get(f"/payments/{payment['payment_id']}/refunds", headers=auth_headers(OTHER_OWNER_ID))

    assert response.status_code == 404
