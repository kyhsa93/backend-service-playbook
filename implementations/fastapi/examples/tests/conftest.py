import os

# 테스트는 main.py import 시점에 validate_env()를 통과해야 한다 — 실제 접속 대상은
# 각 e2e 테스트가 testcontainers로 띄운 뒤 fixture에서 dependency_overrides로 교체한다.
os.environ.setdefault(
    "DATABASE_URL", "postgresql+asyncpg://postgres:postgres@localhost:5432/account"
)
os.environ.setdefault("JWT_SECRET", "test-secret")
