import asyncio
import os
import uuid
from collections.abc import AsyncGenerator
from datetime import date

import httpx
import pytest
import pytest_asyncio
from conftest import (
    create_domain_event_queue,
    create_task_queue,
    start_outbox_background_tasks,
    start_task_queue_background_tasks,
    stop_outbox_background_tasks,
    stop_task_queue_background_tasks,
    wait_until,
)
from httpx import ASGITransport, AsyncClient
from main import app
from sqlalchemy import select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from testcontainers.localstack import LocalStackContainer
from testcontainers.postgres import PostgresContainer

from src.account.infrastructure.notification.sent_email_model import SentEmailModel
from src.account.infrastructure.persistence.account_repository import AccountModel, Base
from src.auth.infrastructure.jwt_auth_service import JwtAuthService
from src.card.application.command.send_monthly_card_statement_handler import previous_month_period
from src.card.infrastructure.notification.sent_statement_email_model import SentStatementEmailModel
from src.card.infrastructure.persistence.card_repository import CardModel
from src.database import get_session
from src.payment.infrastructure.persistence.payment_repository import PaymentModel
from src.task_queue.task_outbox_writer import TaskOutboxWriter

OWNER_ID = "owner-1"
OWNER_EMAIL = "owner1@example.com"
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
async def scheduling_env() -> AsyncGenerator[dict, None]:
    with (
        PostgresContainer("postgres:16-alpine") as postgres,
        LocalStackContainer("localstack/localstack:3.0", region_name="us-east-1").with_services(
            "ses", "sqs"
        ) as localstack,
    ):
        ses_endpoint = localstack.get_url()
        domain_event_queue_url = create_domain_event_queue(localstack)
        task_queue_url = create_task_queue(localstack)

        previous_env = {
            key: os.environ.get(key)
            for key in (
                "AWS_REGION",
                "AWS_ENDPOINT_URL",
                "AWS_ACCESS_KEY_ID",
                "AWS_SECRET_ACCESS_KEY",
                "SES_SENDER_EMAIL",
                "SQS_DOMAIN_EVENT_QUEUE_URL",
                "SQS_TASK_QUEUE_URL",
            )
        }
        os.environ["AWS_REGION"] = "us-east-1"
        os.environ["AWS_ENDPOINT_URL"] = ses_endpoint
        os.environ["AWS_ACCESS_KEY_ID"] = "test"
        os.environ["AWS_SECRET_ACCESS_KEY"] = "test"
        os.environ["SES_SENDER_EMAIL"] = SENDER_EMAIL
        os.environ["SQS_DOMAIN_EVENT_QUEUE_URL"] = domain_event_queue_url
        os.environ["SQS_TASK_QUEUE_URL"] = task_queue_url

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

        # main.py의 lifespan(APScheduler 기동 포함)은 ASGITransport로 구동되는 이 클라이언트에서는
        # 트리거되지 않는다 — 이 fixture가 Outbox/Task 파이프라인을 직접 백그라운드로 띄우고,
        # 각 테스트는 실제 Cron tick을 기다리는 대신 TaskOutboxWriter.enqueue()를 직접 호출해
        # 스케줄된 두 경로를 트리거한다(요구사항: "call the enqueue method directly").
        outbox_tasks = start_outbox_background_tasks(session_factory)
        task_queue_tasks = start_task_queue_background_tasks(session_factory)

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            yield {"client": client, "ses_endpoint": ses_endpoint, "session_factory": session_factory}

        await stop_outbox_background_tasks(outbox_tasks)
        await stop_task_queue_background_tasks(task_queue_tasks)
        await engine.dispose()
        app.dependency_overrides.pop(get_session, None)

        for key, value in previous_env.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


async def _enqueue_task(session_factory, task_type: str, group_id: str) -> None:
    """Scheduler(Cron)가 하는 일을 그대로 재현한다 — 실제 Cron tick을 기다리지 않고
    TaskOutboxWriter.enqueue()를 직접 호출해 같은 트랜잭션 커밋으로 Task를 적재한다
    (scheduling.md "Task Outbox 패턴"과 동일한 경로, 트리거만 테스트가 대신한다)."""
    async with session_factory() as session:
        await TaskOutboxWriter(session).enqueue(
            task_type=task_type,
            payload={},
            group_id=group_id,
            deduplication_id=f"{task_type}-test-{uuid.uuid4().hex}",
        )
        await session.commit()


async def _get_account_row(session_factory, account_id: str) -> AccountModel:
    async with session_factory() as session:
        return await session.get(AccountModel, account_id)


async def _get_card_row(session_factory, card_id: str) -> CardModel:
    async with session_factory() as session:
        return await session.get(CardModel, card_id)


@pytest.mark.asyncio
async def test_정기_이자_지급_task를_enqueue하면_ACTIVE_계좌에_이자가_지급되고_이메일이_발송된다(
    scheduling_env: dict,
) -> None:
    client: AsyncClient = scheduling_env["client"]
    session_factory = scheduling_env["session_factory"]

    create_response = await client.post(
        "/accounts", json={"currency": "KRW", "email": OWNER_EMAIL}, headers=auth_headers(OWNER_ID)
    )
    account_id = create_response.json()["account_id"]
    await client.post(f"/accounts/{account_id}/deposit", json={"amount": 1000000}, headers=auth_headers(OWNER_ID))

    await _enqueue_task(session_factory, "account.interest.apply", "account.interest")

    async def _interest_applied() -> bool:
        row = await _get_account_row(session_factory, account_id)
        return row is not None and row.last_interest_paid_at == date.today()

    await wait_until(_interest_applied, timeout=30.0)

    row = await _get_account_row(session_factory, account_id)
    # 1_000_000 * 0.0001(기본 일일 이자율) = 100
    assert row.amount == 1000100

    async def _interest_email_sent() -> bool:
        async with session_factory() as session:
            stmt = select(SentEmailModel).where(
                SentEmailModel.account_id == account_id, SentEmailModel.event_type == "InterestPaid"
            )
            return (await session.execute(stmt)).scalar_one_or_none() is not None

    await wait_until(_interest_email_sent, timeout=30.0)

    ses_messages = await _fetch_ses_messages(scheduling_env["ses_endpoint"])
    assert any(OWNER_EMAIL in m["Destination"]["ToAddresses"] for m in ses_messages)


@pytest.mark.asyncio
async def test_정기_이자_지급_task가_재전달되어도_같은_날에는_이자가_중복_지급되지_않는다(
    scheduling_env: dict,
) -> None:
    client: AsyncClient = scheduling_env["client"]
    session_factory = scheduling_env["session_factory"]

    create_response = await client.post(
        "/accounts", json={"currency": "KRW", "email": OWNER_EMAIL}, headers=auth_headers(OWNER_ID)
    )
    account_id = create_response.json()["account_id"]
    await client.post(f"/accounts/{account_id}/deposit", json={"amount": 500000}, headers=auth_headers(OWNER_ID))

    await _enqueue_task(session_factory, "account.interest.apply", "account.interest")

    async def _interest_applied() -> bool:
        row = await _get_account_row(session_factory, account_id)
        return row is not None and row.last_interest_paid_at == date.today()

    await wait_until(_interest_applied, timeout=30.0)
    balance_after_first_run = (await _get_account_row(session_factory, account_id)).amount

    # 같은 날 Task가 재전달된 것처럼(at-least-once) 다시 enqueue한다 — 서로 다른
    # deduplication_id를 써서 FIFO 큐 자체의 dedup에 기대지 않고, Aggregate 레벨의 Level 1
    # 멱등성(Account.last_interest_paid_at)만으로 안전한지 검증한다.
    await _enqueue_task(session_factory, "account.interest.apply", "account.interest")

    # 재처리가 실제로 일어날 시간을 준 뒤에도 잔액이 그대로인지 확인한다.

    await asyncio.sleep(5)
    row = await _get_account_row(session_factory, account_id)
    assert row.amount == balance_after_first_run


@pytest.mark.asyncio
async def test_매월_카드_사용내역_발송_task를_enqueue하면_지난달_결제내역이_집계되어_이메일이_발송된다(
    scheduling_env: dict,
) -> None:
    client: AsyncClient = scheduling_env["client"]
    session_factory = scheduling_env["session_factory"]

    create_response = await client.post(
        "/accounts", json={"currency": "KRW", "email": OWNER_EMAIL}, headers=auth_headers(OWNER_ID)
    )
    account_id = create_response.json()["account_id"]

    card_response = await client.post(
        "/cards", json={"account_id": account_id, "brand": "VISA"}, headers=auth_headers(OWNER_ID)
    )
    card_id = card_response.json()["card_id"]

    # API로 결제를 만들면 created_at이 "지금"(이번 달)이 되어 "지난달" 집계 창에 들어오지
    # 않는다 — 배치가 집계하는 지난 한 달 범위에 실제 데이터를 채우기 위해, 그 범위 안의
    # created_at을 갖는 COMPLETED 결제 2건을 세션으로 직접 적재한다(다른 e2e 테스트들이
    # OutboxModel.processed를 직접 조작하는 것과 동일한, 이 저장소가 이미 쓰는 방식).
    period, since, _until = previous_month_period(date.today())
    async with session_factory() as session:
        session.add(
            PaymentModel(
                id=uuid.uuid4().hex,
                card_id=card_id,
                account_id=account_id,
                owner_id=OWNER_ID,
                amount=7000,
                status="COMPLETED",
                created_at=since,
            )
        )
        session.add(
            PaymentModel(
                id=uuid.uuid4().hex,
                card_id=card_id,
                account_id=account_id,
                owner_id=OWNER_ID,
                amount=3000,
                status="COMPLETED",
                created_at=since,
            )
        )
        await session.commit()

    await _enqueue_task(session_factory, "card.statement.send", "card.statement")

    async def _statement_sent() -> bool:
        row = await _get_card_row(session_factory, card_id)
        return row is not None and row.last_statement_sent_month == period

    await wait_until(_statement_sent, timeout=30.0)

    async def _statement_email_recorded() -> bool:
        async with session_factory() as session:
            stmt = select(SentStatementEmailModel).where(SentStatementEmailModel.card_id == card_id)
            return (await session.execute(stmt)).scalar_one_or_none() is not None

    await wait_until(_statement_email_recorded, timeout=30.0)

    ses_messages = await _fetch_ses_messages(scheduling_env["ses_endpoint"])
    assert any(OWNER_EMAIL in m["Destination"]["ToAddresses"] for m in ses_messages)

    # 실제 발송 이력(Ledger)의 subject에 집계 기간이 반영됐는지 확인한다 — 건수·합계 자체의
    # 정확한 계산 로직은 PaymentAdapterImpl/SendMonthlyCardStatementHandler의 단위 테스트가
    # 검증하므로, e2e에서는 전체 파이프라인(enqueue → Task 큐 → Consumer → Handler → Domain
    # Event → Outbox → 큐 → Consumer → SES 발송 + Ledger 기록)이 실제로 동작하는지에 집중한다.
    async with session_factory() as session:
        stmt = select(SentStatementEmailModel).where(SentStatementEmailModel.card_id == card_id)
        sent = (await session.execute(stmt)).scalar_one()
        assert period in sent.subject
        assert sent.recipient == OWNER_EMAIL


@pytest.mark.asyncio
async def test_이번달_이미_발송된_카드는_같은_task가_재전달되어도_중복_발송되지_않는다(
    scheduling_env: dict,
) -> None:
    client: AsyncClient = scheduling_env["client"]
    session_factory = scheduling_env["session_factory"]

    create_response = await client.post(
        "/accounts", json={"currency": "KRW", "email": OWNER_EMAIL}, headers=auth_headers(OWNER_ID)
    )
    account_id = create_response.json()["account_id"]
    card_response = await client.post(
        "/cards", json={"account_id": account_id, "brand": "VISA"}, headers=auth_headers(OWNER_ID)
    )
    card_id = card_response.json()["card_id"]

    period, _since, _until = previous_month_period(date.today())

    await _enqueue_task(session_factory, "card.statement.send", "card.statement")

    async def _statement_sent() -> bool:
        row = await _get_card_row(session_factory, card_id)
        return row is not None and row.last_statement_sent_month == period

    await wait_until(_statement_sent, timeout=30.0)

    async def _sent_email_count() -> int:
        async with session_factory() as session:
            stmt = select(SentStatementEmailModel).where(SentStatementEmailModel.card_id == card_id)
            return len((await session.execute(stmt)).scalars().all())

    async def _email_recorded_at_least_once() -> bool:
        return await _sent_email_count() > 0

    # card.last_statement_sent_month가 갱신된 시점은 CardStatementSent 이벤트가 Outbox에
    # "적재"된 시점일 뿐, SES 발송(Outbox → SQS → OutboxConsumer 왕복)은 아직 안 끝났을 수
    # 있다 — 실제로 Ledger 행이 생길 때까지 별도로 기다린다(vacuous pass 방지).
    await wait_until(_email_recorded_at_least_once, timeout=30.0)

    first_count = await _sent_email_count()
    assert first_count == 1

    # 같은 달에 Task가 다시 enqueue된 것처럼(at-least-once) 재전달을 시뮬레이션한다.
    await _enqueue_task(session_factory, "card.statement.send", "card.statement")

    await asyncio.sleep(5)
    assert await _sent_email_count() == first_count
