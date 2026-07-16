from collections.abc import AsyncGenerator

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from testcontainers.postgres import PostgresContainer

from main import app
from src.account.infrastructure.persistence.account_repository import Base
from src.database import get_session

PASSWORD = "password123!"


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
        app.dependency_overrides.pop(get_session, None)


async def sign_up(client: AsyncClient, user_id: str, password: str = PASSWORD):
    return await client.post("/auth/sign-up", json={"user_id": user_id, "password": password})


async def sign_in(client: AsyncClient, user_id: str, password: str = PASSWORD):
    return await client.post("/auth/sign-in", json={"user_id": user_id, "password": password})


@pytest.mark.asyncio
async def test_sign_up_후_sign_in하면_201과_액세스_토큰을_반환한다(client: AsyncClient) -> None:
    sign_up_response = await sign_up(client, "owner-1")
    assert sign_up_response.status_code == 201

    sign_in_response = await sign_in(client, "owner-1")

    assert sign_in_response.status_code == 201
    body = sign_in_response.json()
    assert isinstance(body["access_token"], str)
    assert body["access_token"]


@pytest.mark.asyncio
async def test_sign_in_비밀번호가_틀리면_401과_INVALID_CREDENTIALS를_반환한다(client: AsyncClient) -> None:
    await sign_up(client, "owner-2")

    response = await sign_in(client, "owner-2", password="wrong-password")

    assert response.status_code == 401
    body = response.json()
    assert body["statusCode"] == 401
    assert body["code"] == "INVALID_CREDENTIALS"
    assert body["error"] == "Unauthorized"


@pytest.mark.asyncio
async def test_sign_in_존재하지_않는_아이디면_401과_INVALID_CREDENTIALS를_반환한다(client: AsyncClient) -> None:
    response = await sign_in(client, "no-such-user")

    assert response.status_code == 401
    body = response.json()
    assert body["statusCode"] == 401
    assert body["code"] == "INVALID_CREDENTIALS"
    assert body["error"] == "Unauthorized"


@pytest.mark.asyncio
async def test_sign_in_틀린_비밀번호와_존재하지_않는_아이디는_동일한_메시지를_반환한다(client: AsyncClient) -> None:
    await sign_up(client, "owner-3")

    wrong_password_response = await sign_in(client, "owner-3", password="wrong-password")
    nonexistent_user_response = await sign_in(client, "no-such-user-2")

    assert wrong_password_response.json()["message"] == nonexistent_user_response.json()["message"]


@pytest.mark.asyncio
async def test_sign_up_이미_사용중인_아이디면_400과_USER_ID_ALREADY_EXISTS를_반환한다(client: AsyncClient) -> None:
    await sign_up(client, "owner-4")

    response = await sign_up(client, "owner-4", password="another-password")

    assert response.status_code == 400
    body = response.json()
    assert body["statusCode"] == 400
    assert body["code"] == "USER_ID_ALREADY_EXISTS"
    assert body["error"] == "Bad Request"


@pytest.mark.asyncio
async def test_sign_up_비밀번호가_8자_미만이면_422와_VALIDATION_FAILED를_반환한다(client: AsyncClient) -> None:
    response = await sign_up(client, "owner-5", password="short")

    assert response.status_code == 422
    body = response.json()
    assert body["statusCode"] == 422
    assert body["code"] == "VALIDATION_FAILED"
    assert body["error"] == "Unprocessable Entity"
