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
    # AuthService.issue_token/verify_token은 동기 메서드다(src/auth/application/service/auth_service.py) —
    # AsyncMock을 쓰면 await 없이 호출했을 때 코루틴 객체가 반환되어 실제 구현과 다르게 동작한다.
    return MagicMock()


@pytest.mark.asyncio
async def test_execute_아이디와_비밀번호가_일치하면_액세스_토큰을_발급한다(repo, password_hasher, auth_service) -> None:
    credential = Credential.create(user_id="owner-1", password_hash="hashed-password")
    repo.find_by_user_id.return_value = credential
    password_hasher.verify.return_value = True
    auth_service.issue_token.return_value = "access-token"
    handler = SignInHandler(repo, password_hasher, auth_service)

    access_token = await handler.execute(SignInCommand(user_id="owner-1", password="plain-password"))

    password_hasher.verify.assert_awaited_once_with("plain-password", "hashed-password")
    auth_service.issue_token.assert_called_once_with("owner-1")
    assert access_token == "access-token"


@pytest.mark.asyncio
async def test_execute_존재하지_않는_아이디면_에러를_raise한다(repo, password_hasher, auth_service) -> None:
    repo.find_by_user_id.return_value = None
    handler = SignInHandler(repo, password_hasher, auth_service)

    with pytest.raises(InvalidCredentialsError):
        await handler.execute(SignInCommand(user_id="no-such-user", password="plain-password"))

    auth_service.issue_token.assert_not_called()


@pytest.mark.asyncio
async def test_execute_비밀번호가_틀리면_에러를_raise한다(repo, password_hasher, auth_service) -> None:
    credential = Credential.create(user_id="owner-1", password_hash="hashed-password")
    repo.find_by_user_id.return_value = credential
    password_hasher.verify.return_value = False
    handler = SignInHandler(repo, password_hasher, auth_service)

    with pytest.raises(InvalidCredentialsError):
        await handler.execute(SignInCommand(user_id="owner-1", password="wrong-password"))

    auth_service.issue_token.assert_not_called()
