from collections.abc import AsyncGenerator

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from .config.database_config import DatabaseConfig

engine = create_async_engine(DatabaseConfig().url, echo=False)  # type: ignore[call-arg]
SessionLocal = async_sessionmaker(engine, expire_on_commit=False)


async def get_session() -> AsyncGenerator[AsyncSession, None]:
    # This gap became a real risk once a use case like transfer (an inter-account transfer)
    # started saving two different Aggregate instances sequentially within a single request
    # — if the second save throws, the first save may already be left flushed to the
    # session, and without this except, `async with SessionLocal()` would just close the
    # session with no rollback (risking a partial commit depending on driver/connection-pool
    # behavior — see persistence.md). Rolling back explicitly on an exception turns this
    # assumption into a guarantee.
    async with SessionLocal() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
