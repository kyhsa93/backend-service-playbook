from datetime import datetime

from sqlalchemy import select
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

    async def find_by_user_id(self, user_id: str) -> Credential | None:
        stmt = select(CredentialModel).where(CredentialModel.user_id == user_id)
        row = (await self._session.execute(stmt)).scalar_one_or_none()
        if row is None:
            return None
        return Credential(
            credential_id=row.id,
            user_id=row.user_id,
            password_hash=row.password_hash,
            created_at=row.created_at,
        )

    async def save(self, credential: Credential) -> None:
        self._session.add(CredentialModel(
            id=credential.credential_id,
            user_id=credential.user_id,
            password_hash=credential.password_hash,
            created_at=credential.created_at,
        ))
        await self._session.flush()
