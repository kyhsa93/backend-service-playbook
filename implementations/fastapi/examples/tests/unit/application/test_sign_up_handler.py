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
async def test_execute_신규_아이디면_비밀번호를_해싱해서_저장한다(repo, password_hasher) -> None:
    repo.find_by_user_id.return_value = None
    password_hasher.hash.return_value = "hashed-password"
    handler = SignUpHandler(repo, password_hasher)

    await handler.execute(SignUpCommand(user_id="owner-1", password="plain-password"))

    password_hasher.hash.assert_awaited_once_with("plain-password")
    saved = repo.save.call_args.args[0]
    assert saved.user_id == "owner-1"
    assert saved.password_hash == "hashed-password"


@pytest.mark.asyncio
async def test_execute_이미_존재하는_아이디면_에러를_raise하고_저장하지_않는다(repo, password_hasher) -> None:
    repo.find_by_user_id.return_value = Credential.create(user_id="owner-1", password_hash="existing-hash")
    handler = SignUpHandler(repo, password_hasher)

    with pytest.raises(UserIdAlreadyExistsError):
        await handler.execute(SignUpCommand(user_id="owner-1", password="plain-password"))

    repo.save.assert_not_awaited()
    password_hasher.hash.assert_not_awaited()
