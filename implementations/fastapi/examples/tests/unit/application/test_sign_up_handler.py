from unittest.mock import AsyncMock

import pytest

from src.auth.application.command.sign_up_handler import SignUpCommand, SignUpHandler
from src.auth.domain.credential import Credential
from src.auth.domain.errors import UserIdAlreadyExistsError


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()


@pytest.fixture
def password_hasher() -> AsyncMock:
    return AsyncMock()


@pytest.mark.asyncio
async def test_execute_hashes_and_saves_the_password_for_a_new_username(repo, password_hasher) -> None:
    repo.find_credentials.return_value = ([], 0)
    password_hasher.hash.return_value = "hashed-password"
    handler = SignUpHandler(repo, password_hasher)

    await handler.execute(SignUpCommand(user_id="owner-1", password="plain-password"))

    password_hasher.hash.assert_awaited_once_with("plain-password")
    saved = repo.save_credential.call_args.args[0]
    assert saved.user_id == "owner-1"
    assert saved.password_hash == "hashed-password"


@pytest.mark.asyncio
async def test_execute_raises_an_error_and_does_not_save_when_username_already_exists(repo, password_hasher) -> None:
    existing = Credential.create(user_id="owner-1", password_hash="existing-hash")
    repo.find_credentials.return_value = ([existing], 1)
    handler = SignUpHandler(repo, password_hasher)

    with pytest.raises(UserIdAlreadyExistsError):
        await handler.execute(SignUpCommand(user_id="owner-1", password="plain-password"))

    repo.save_credential.assert_not_awaited()
    password_hasher.hash.assert_not_awaited()
