import os
from datetime import datetime, timedelta, timezone

from jose import JWTError, jwt

from ..application.service.auth_service import AuthService
from ..domain.errors import InvalidTokenError

JWT_ALGORITHM = "HS256"
ACCESS_TOKEN_TTL = timedelta(hours=1)

# 프로덕션에서는 main.py의 lifespan 기동 시 Secrets Manager에서 조회한 값으로
# set_jwt_secret()이 이 값을 채운다. 그 전까지는 환경 변수를 쓴다 — validate_env()
# (config/validator.py)가 프로덕션이 아닌 환경에서 JWT_SECRET 누락을 이미 fail-fast로
# 막았으므로, 여기서는 더 이상 "dev-secret" 같은 조용한 기본값을 두지 않는다.
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
