from unittest.mock import AsyncMock, MagicMock

import pytest

from src.auth.application.command.sign_in_handler import SignInCommand, SignInHandler
from src.auth.domain.credential import Credential
from src.auth.domain.errors import InvalidCredentialsError


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()


@pytest.fixture
def password_hasher() -> AsyncMock:
    return AsyncMock()


@pytest.fixture
def auth_service() -> MagicMock:
    # AuthService.issue_token/verify_token are synchronous methods
    # (src/auth/application/service/auth_service.py) — using AsyncMock would return a coroutine
    # object when called without await, behaving differently from the real implementation.
    return MagicMock()


@pytest.mark.asyncio
async def test_execute_issues_an_access_token_when_username_and_password_match(
    repo, password_hasher, auth_service
) -> None:
    credential = Credential.create(user_id="owner-1", password_hash="hashed-password")
    repo.find_credentials.return_value = ([credential], 1)
    password_hasher.verify.return_value = True
    auth_service.issue_token.return_value = "access-token"
    handler = SignInHandler(repo, password_hasher, auth_service)

    access_token = await handler.execute(SignInCommand(user_id="owner-1", password="plain-password"))

    password_hasher.verify.assert_awaited_once_with("plain-password", "hashed-password")
    auth_service.issue_token.assert_called_once_with("owner-1")
    assert access_token == "access-token"


@pytest.mark.asyncio
async def test_execute_raises_an_error_when_username_does_not_exist(repo, password_hasher, auth_service) -> None:
    repo.find_credentials.return_value = ([], 0)
    handler = SignInHandler(repo, password_hasher, auth_service)

    with pytest.raises(InvalidCredentialsError):
        await handler.execute(SignInCommand(user_id="no-such-user", password="plain-password"))

    auth_service.issue_token.assert_not_called()


@pytest.mark.asyncio
async def test_execute_raises_an_error_when_password_is_wrong(repo, password_hasher, auth_service) -> None:
    credential = Credential.create(user_id="owner-1", password_hash="hashed-password")
    repo.find_credentials.return_value = ([credential], 1)
    password_hasher.verify.return_value = False
    handler = SignInHandler(repo, password_hasher, auth_service)

    with pytest.raises(InvalidCredentialsError):
        await handler.execute(SignInCommand(user_id="owner-1", password="wrong-password"))

    auth_service.issue_token.assert_not_called()
