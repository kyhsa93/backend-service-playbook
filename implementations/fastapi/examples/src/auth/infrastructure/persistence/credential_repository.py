from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import Mapped, mapped_column

from ....account.infrastructure.persistence.account_repository import Base
from ...domain.credential import Credential
from ...domain.repository import CredentialRepository


class CredentialModel(Base):
    __tablename__ = "credentials"

    id: Mapped[str] = mapped_column(primary_key=True)
    user_id: Mapped[str] = mapped_column(unique=True, index=True)
    password_hash: Mapped[str]
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)


class SqlAlchemyCredentialRepository(CredentialRepository):
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def find_credentials(
        self, page: int, take: int, credential_id: str | None = None, user_id: str | None = None
    ) -> tuple[list[Credential], int]:
        stmt = select(CredentialModel)
        if credential_id:
            stmt = stmt.where(CredentialModel.id == credential_id)
        if user_id:
            stmt = stmt.where(CredentialModel.user_id == user_id)
        count_stmt = select(func.count()).select_from(stmt.subquery())
        total = (await self._session.execute(count_stmt)).scalar_one()
        rows = (await self._session.execute(stmt.offset(page * take).limit(take))).scalars().all()
        credentials = [
            Credential(
                credential_id=row.id,
                user_id=row.user_id,
                password_hash=row.password_hash,
                created_at=row.created_at,
            )
            for row in rows
        ]
        return credentials, total

    async def save_credential(self, credential: Credential) -> None:
        self._session.add(
            CredentialModel(
                id=credential.credential_id,
                user_id=credential.user_id,
                password_hash=credential.password_hash,
                created_at=credential.created_at,
            )
        )
        await self._session.flush()
