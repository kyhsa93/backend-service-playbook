from dataclasses import dataclass

from ...domain.errors import InvalidCredentialsError
from ...domain.repository import CredentialRepository
from ..service.auth_service import AuthService
from ..service.password_hasher import PasswordHasher


@dataclass
class SignInCommand:
    user_id: str
    password: str


class SignInHandler:
    def __init__(self, repo: CredentialRepository, password_hasher: PasswordHasher, auth_service: AuthService) -> None:
        self._repo = repo
        self._password_hasher = password_hasher
        self._auth_service = auth_service

    async def execute(self, cmd: SignInCommand) -> str:
        credentials, _ = await self._repo.find_credentials(page=0, take=1, user_id=cmd.user_id)
        credential = credentials[0] if credentials else None
        # Responds with the same error whether the username doesn't exist or the password doesn't match
        # — prevents user enumeration
        if credential is None:
            raise InvalidCredentialsError()

        is_valid = await self._password_hasher.verify(cmd.password, credential.password_hash)
        if not is_valid:
            raise InvalidCredentialsError()

        return self._auth_service.issue_token(credential.user_id)
