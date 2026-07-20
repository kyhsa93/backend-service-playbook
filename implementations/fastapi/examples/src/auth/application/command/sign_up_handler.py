from dataclasses import dataclass

from ...domain.credential import Credential
from ...domain.errors import UserIdAlreadyExistsError
from ...domain.repository import CredentialRepository
from ..service.password_hasher import PasswordHasher


@dataclass
class SignUpCommand:
    user_id: str
    password: str


class SignUpHandler:
    def __init__(self, repo: CredentialRepository, password_hasher: PasswordHasher) -> None:
        self._repo = repo
        self._password_hasher = password_hasher

    async def execute(self, cmd: SignUpCommand) -> None:
        existing, _ = await self._repo.find_credentials(page=0, take=1, user_id=cmd.user_id)
        if existing:
            raise UserIdAlreadyExistsError()

        password_hash = await self._password_hasher.hash(cmd.password)
        credential = Credential.create(user_id=cmd.user_id, password_hash=password_hash)
        await self._repo.save_credential(credential)
