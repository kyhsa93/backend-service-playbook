import os
import sys

from pydantic import ValidationError

from .database_config import DatabaseConfig
from .jwt_config import JwtConfig
from .sqs_config import SqsConfig


def validate_env() -> None:
    try:
        DatabaseConfig()  # type: ignore[call-arg]  — 값은 환경 변수에서 채워짐
        jwt_config = JwtConfig()  # type: ignore[call-arg]
        SqsConfig()  # type: ignore[call-arg]  — OutboxPoller/OutboxConsumer가 공유하는 큐 URL
    except ValidationError as exc:
        print(f"환경 변수 검증 실패:\n{exc}", file=sys.stderr)
        sys.exit(1)

    # 프로덕션은 lifespan 기동 시 Secrets Manager(app/jwt)가 secret을 채우므로
    # JWT_SECRET 환경 변수가 없어도 된다 — 그 외 환경에서는 명시적으로 필요하다.
    if os.getenv("APP_ENV") != "production" and not jwt_config.secret:
        print("환경 변수 검증 실패:\nJWT_SECRET이 설정되어 있지 않습니다.", file=sys.stderr)
        sys.exit(1)
