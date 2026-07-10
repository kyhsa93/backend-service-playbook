import os
from datetime import datetime, timedelta, timezone

from jose import JWTError, jwt

from ..application.service.auth_service import AuthService
from ..domain.errors import InvalidTokenError

JWT_SECRET = os.getenv("JWT_SECRET", "dev-secret")
JWT_ALGORITHM = "HS256"
ACCESS_TOKEN_TTL = timedelta(hours=1)


class JwtAuthService(AuthService):
    def issue_token(self, user_id: str) -> str:
        payload = {
            "user_id": user_id,
            "exp": datetime.now(timezone.utc) + ACCESS_TOKEN_TTL,
        }
        return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)

    def verify_token(self, token: str) -> str:
        try:
            payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
        except JWTError as exc:
            raise InvalidTokenError() from exc
        return payload["user_id"]
