import os

# 테스트는 main.py import 시점에 validate_env()를 통과해야 한다 — 실제 접속 대상은
# 각 e2e 테스트가 testcontainers로 띄운 뒤 fixture에서 dependency_overrides로 교체한다.
os.environ.setdefault(
    "DATABASE_URL", "postgresql+asyncpg://postgres:postgres@localhost:5432/account"
)
os.environ.setdefault("JWT_SECRET", "test-secret")

# e2e 테스트는 같은 프로세스 안에서 같은 클라이언트 IP로 수십 개 요청을 짧은 시간에 보낸다 —
# 운영 기본값(RATE_LIMIT_WRITE=20/minute 등)을 그대로 쓰면 slowapi가 테스트 도중에도
# 429를 반환해 무관한 실패를 만든다. rate_limit_config.py가 환경 변수로 값을 읽으므로
# 여기서만 넉넉하게 override한다(rate-limiting.md "운영값 조정" 참고).
os.environ.setdefault("RATE_LIMIT_DEFAULT", "100000/minute")
os.environ.setdefault("RATE_LIMIT_WRITE", "100000/minute")
