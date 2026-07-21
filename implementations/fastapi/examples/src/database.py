from collections.abc import AsyncGenerator

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from .config.database_config import DatabaseConfig

engine = create_async_engine(DatabaseConfig().url, echo=False)  # type: ignore[call-arg]
SessionLocal = async_sessionmaker(engine, expire_on_commit=False)


async def get_session() -> AsyncGenerator[AsyncSession, None]:
    # transfer(계좌 간 송금)처럼 한 요청 안에서 서로 다른 두 Aggregate 인스턴스를 순차로
    # 저장하는 유스케이스가 생기면서 이 gap이 실제 위험이 됐다 — 두 번째 저장이 예외를
    # 던지면 첫 번째 저장이 세션에 이미 flush된 상태로 남을 수 있는데, 여기 except가
    # 없으면 `async with SessionLocal()`이 롤백 없이 세션을 그냥 닫는다(드라이버/커넥션 풀
    # 동작에 따라 부분 반영 위험이 있다 — persistence.md 참고). 예외 시 명시적으로
    # 롤백해 이 가정을 보장으로 바꾼다.
    async with SessionLocal() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
