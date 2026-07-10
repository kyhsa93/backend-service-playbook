from dataclasses import dataclass

from fastapi import Depends
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from ...infrastructure.jwt_auth_service import JwtAuthService

_bearer_scheme = HTTPBearer()


@dataclass(frozen=True)
class CurrentUser:
    user_id: str


def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(_bearer_scheme),
) -> CurrentUser:
    auth_service = JwtAuthService()
    user_id = auth_service.verify_token(credentials.credentials)
    return CurrentUser(user_id=user_id)
