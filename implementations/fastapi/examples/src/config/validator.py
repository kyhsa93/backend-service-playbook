import os
import sys

from pydantic import ValidationError

from .database_config import DatabaseConfig
from .jwt_config import JwtConfig
from .sqs_config import SqsConfig


def validate_env() -> None:
    try:
        DatabaseConfig()  # type: ignore[call-arg]  — the value is filled from an environment variable
        jwt_config = JwtConfig()  # type: ignore[call-arg]
        SqsConfig()  # type: ignore[call-arg]  — the queue URLs shared by OutboxPoller/OutboxConsumer
    except ValidationError as exc:
        print(f"Environment variable validation failed:\n{exc}", file=sys.stderr)
        sys.exit(1)

    # In production, Secrets Manager (app/jwt) fills the secret at lifespan startup, so the
    # JWT_SECRET environment variable can be absent — in every other environment it's explicitly required.
    if os.getenv("APP_ENV") != "production" and not jwt_config.secret:
        print("Environment variable validation failed:\nJWT_SECRET is not set.", file=sys.stderr)
        sys.exit(1)
