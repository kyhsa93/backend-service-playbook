from dataclasses import dataclass

from fastapi import Depends, Request
from fastapi.exceptions import HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from ...domain.errors import InvalidTokenError
from ...infrastructure.jwt_auth_service import JwtAuthService


class _Bearer(HTTPBearer):
    """`HTTPBearer`'s own `auto_error=True` behavior (a missing header or the wrong scheme)
    raises a plain `HTTPException(401, "Not authenticated")`, whose body is `{"detail":
    ...}` — not this repository's 4-field error-response shape (error-handling.md). Catching
    it here and re-raising it as `InvalidTokenError` routes every "the client isn't
    authenticated" case — missing header, wrong scheme, or an invalid/expired token — through
    the single `@app.exception_handler(InvalidTokenError)` in main.py, so the response body is
    always the same shape regardless of which of those three actually happened.
    """

    async def __call__(self, request: Request) -> HTTPAuthorizationCredentials:
        try:
            return await super().__call__(request)  # type: ignore[return-value]
        except HTTPException as exc:
            raise InvalidTokenError() from exc


_bearer_scheme = _Bearer()


@dataclass(frozen=True)
class CurrentUser:
    user_id: str


def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(_bearer_scheme),
) -> CurrentUser:
    auth_service = JwtAuthService()
    user_id = auth_service.verify_token(credentials.credentials)
    return CurrentUser(user_id=user_id)
