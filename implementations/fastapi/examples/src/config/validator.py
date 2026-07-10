import sys

from pydantic import ValidationError

from .database_config import DatabaseConfig


def validate_env() -> None:
    try:
        DatabaseConfig()  # type: ignore[call-arg]  — 값은 환경 변수에서 채워짐
    except ValidationError as exc:
        print(f"환경 변수 검증 실패:\n{exc}", file=sys.stderr)
        sys.exit(1)
