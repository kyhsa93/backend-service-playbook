import os
from datetime import datetime, timedelta, timezone

from jose import JWTError, jwt

from ..application.service.auth_service import AuthService
from ..domain.errors import InvalidTokenError

JWT_ALGORITHM = "HS256"
ACCESS_TOKEN_TTL = timedelta(hours=1)

# In production, set_jwt_secret() fills in this value from what's looked up in Secrets
# Manager at main.py's lifespan startup. Until then, the environment variable is used —
# since validate_env() (config/validator.py) already blocks a missing JWT_SECRET via
# fail-fast in non-production environments, no silent default such as "dev-secret" is kept
# here anymore.
_jwt_secret: str = os.getenv("JWT_SECRET", "")


def set_jwt_secret(secret: str) -> None:
    global _jwt_secret
    _jwt_secret = secret


class JwtAuthService(AuthService):
    def issue_token(self, user_id: str) -> str:
        payload = {
            "user_id": user_id,
            "exp": datetime.now(timezone.utc) + ACCESS_TOKEN_TTL,
        }
        return jwt.encode(payload, _jwt_secret, algorithm=JWT_ALGORITHM)

    def verify_token(self, token: str) -> str:
        try:
            payload = jwt.decode(token, _jwt_secret, algorithms=[JWT_ALGORITHM])
        except JWTError as exc:
            raise InvalidTokenError() from exc
        return payload["user_id"]
