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

# 다른 e2e 테스트 파일(test_account_e2e.py/test_card_e2e.py)과 동일하게, 이 파일도 자신만의
# session-scope `client` fixture로 별도의 Postgres/LocalStack testcontainer를 띄운다 —
# 파일 간에는 공유하지 않으므로 서로 오염되지 않는다(파일 내 여러 테스트 함수 사이의 오염은
# 각 테스트가 자신만의 신규 계좌/카드를 만들어 피한다).
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


# --- 동기 Adapter(ACL) 흐름: POST /payments가 Card/Account BC를 즉시 조회해 결제 가부를 판정한다 ---


@pytest.mark.asyncio
async def test_create_payment_success_debits_account_immediately(client: AsyncClient) -> None:
    account, card = await make_funded_card(client, OWNER_ID, balance=100000)

    payment = await create_payment(client, OWNER_ID, card["card_id"], 30000)

    assert payment["status"] == "COMPLETED"
    assert payment["account_id"] == account["account_id"]
    assert payment["amount"] == 30000

    # payment.completed.v1이 OutboxPoller → SQS → OutboxConsumer를 거쳐 비동기로 처리되어
    # 잔액이 반영된다 — 응답 직후 즉시 반영되어 있지 않을 수 있으므로 폴링한다
    # (test_card_e2e.py의 account.suspended.v1과 동일 패턴).
    await wait_until(lambda: _balance_is(client, OWNER_ID, account["account_id"], 70000))


@pytest.mark.asyncio
async def test_create_payment_inactive_card_returns_400(client: AsyncClient) -> None:
    account, card = await make_funded_card(client, OWNER_ID, balance=100000)
    # 계좌를 정지하면 account.suspended.v1을 Card BC가 구독해 카드도 비동기로 정지된다.
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
    card = await issue_card(client, OWNER_ID, account["account_id"])  # 잔액 0

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


# --- 비동기 Integration Event 흐름: 결제취소가 보상 크레딧을 트리거한다 ---


@pytest.mark.asyncio
async def test_cancel_payment_restores_balance_via_compensating_credit(client: AsyncClient) -> None:
    account, card = await make_funded_card(client, OWNER_ID, balance=100000)
    payment = await create_payment(client, OWNER_ID, card["card_id"], 30000)
    # payment.completed.v1이 비동기로 처리되어 잔액이 반영될 때까지 기다린다.
    await wait_until(lambda: _balance_is(client, OWNER_ID, account["account_id"], 70000))

    cancel_response = await client.post(
        f"/payments/{payment['payment_id']}/cancel", json={"reason": "고객 요청"}, headers=auth_headers(OWNER_ID)
    )
    assert cancel_response.status_code == 204

    # payment.cancelled.v1이 OutboxPoller → SQS → OutboxConsumer를 거쳐 비동기로 처리되어
    # 보상 크레딧으로 잔액이 복구된다.
    await wait_until(lambda: _balance_is(client, OWNER_ID, account["account_id"], 100000))

    get_payment_response = await client.get(f"/payments/{payment['payment_id']}", headers=auth_headers(OWNER_ID))
    assert get_payment_response.json()["status"] == "CANCELLED"


@pytest.mark.asyncio
async def test_cancel_pending_or_already_cancelled_payment_returns_400(client: AsyncClient) -> None:
    account, card = await make_funded_card(client, OWNER_ID, balance=100000)
    payment = await create_payment(client, OWNER_ID, card["card_id"], 10000)
    await client.post(
        f"/payments/{payment['payment_id']}/cancel", json={"reason": "1차 취소"}, headers=auth_headers(OWNER_ID)
    )

    response = await client.post(
        f"/payments/{payment['payment_id']}/cancel", json={"reason": "2차 취소"}, headers=auth_headers(OWNER_ID)
    )

    assert response.status_code == 400
    assert response.json()["code"] == "PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT"


# --- RefundEligibilityService(Domain Service) 판단 검증 ---


@pytest.mark.asyncio
async def test_refund_within_payment_amount_is_approved_and_credits_account(client: AsyncClient) -> None:
    account, card = await make_funded_card(client, OWNER_ID, balance=100000)
    payment = await create_payment(client, OWNER_ID, card["card_id"], 30000)
    await wait_until(lambda: _balance_is(client, OWNER_ID, account["account_id"], 70000))

    response = await client.post(
        f"/payments/{payment['payment_id']}/refunds",
        json={"amount": 30000, "reason": "단순 변심"},
        headers=auth_headers(OWNER_ID),
    )

    assert response.status_code == 201
    body = response.json()
    assert body["status"] == "APPROVED"
    assert body["decision_note"] == "환불이 승인되었습니다."

    # refund.approved.v1이 OutboxPoller → SQS → OutboxConsumer를 거쳐 비동기로 처리되어
    # 환불 크레딧이 반영된다.
    await wait_until(lambda: _balance_is(client, OWNER_ID, account["account_id"], 100000))


@pytest.mark.asyncio
async def test_refund_exceeding_payment_amount_is_rejected_with_201(client: AsyncClient) -> None:
    account, card = await make_funded_card(client, OWNER_ID, balance=100000)
    payment = await create_payment(client, OWNER_ID, card["card_id"], 30000)

    response = await client.post(
        f"/payments/{payment['payment_id']}/refunds",
        json={"amount": 30001, "reason": "단순 변심"},
        headers=auth_headers(OWNER_ID),
    )

    # 환불 거부는 유효한 도메인 판단 결과다 — 4xx 에러가 아니라 201 + status: REJECTED로 응답한다.
    assert response.status_code == 201
    body = response.json()
    assert body["status"] == "REJECTED"
    assert body["decision_note"] == "환불 금액은 결제 금액을 초과할 수 없습니다."

    # 거부된 환불은 Domain Event가 없으므로 잔액에 아무 영향을 주지 않는다 — 다만 결제
    # 자체의 debit(payment.completed.v1)은 비동기이므로, 70000으로 반영될 때까지 기다린다.
    await wait_until(lambda: _balance_is(client, OWNER_ID, account["account_id"], 70000))


@pytest.mark.asyncio
async def test_refund_on_non_completed_payment_is_rejected_with_201(client: AsyncClient) -> None:
    account, card = await make_funded_card(client, OWNER_ID, balance=100000)
    payment = await create_payment(client, OWNER_ID, card["card_id"], 30000)
    await client.post(
        f"/payments/{payment['payment_id']}/cancel", json={"reason": "고객 요청"}, headers=auth_headers(OWNER_ID)
    )

    response = await client.post(
        f"/payments/{payment['payment_id']}/refunds",
        json={"amount": 30000, "reason": "단순 변심"},
        headers=auth_headers(OWNER_ID),
    )

    assert response.status_code == 201
    body = response.json()
    assert body["status"] == "REJECTED"
    assert body["decision_note"] == "완료된 결제에 대해서만 환불을 요청할 수 있습니다."


@pytest.mark.asyncio
async def test_request_refund_payment_not_found_returns_404(client: AsyncClient) -> None:
    response = await client.post(
        "/payments/non-existent/refunds", json={"amount": 1000, "reason": "단순 변심"}, headers=auth_headers(OWNER_ID)
    )

    assert response.status_code == 404
    assert response.json()["code"] == "PAYMENT_NOT_FOUND"


# --- 조회(Query) — 인증된 요청자 본인 소유로만 스코프된다 ---


@pytest.mark.asyncio
async def test_get_payments_lists_only_requesters_own_payments(client: AsyncClient) -> None:
    # client fixture는 세션 전체(다른 e2e 테스트 파일 포함)에서 같은 Postgres를 공유하므로,
    # 개수를 정확히 검증하는 이 테스트는 공용 OWNER_ID/OTHER_OWNER_ID를 재사용하지 않고
    # 이 테스트만의 고유한 owner id를 쓴다 — 그래야 다른 테스트가 만든 결제 데이터와
    # 섞이지 않는다.
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
        json={"amount": 30001, "reason": "초과 환불 시도"},
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
