# 환경 설정 관리

> 프레임워크 무관 원칙: [../../../../docs/architecture/config.md](../../../../docs/architecture/config.md)

## 알려진 격차 — 현재는 fail-fast 검증이 없다

`src/database.py`가 환경 설정을 다루는 유일한 곳이다.

```python
# 현재 examples/ 코드
DATABASE_URL = os.getenv(
    "DATABASE_URL", "postgresql+asyncpg://postgres:postgres@localhost:5432/account"
)
```

`SES_SENDER_EMAIL`, `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`(`src/account/infrastructure/notification/notification_service.py`)도 마찬가지로 `os.getenv(..., "test")`류의 하드코딩된 기본값을 쓴다. 필수 값이 비어 있어도 앱이 기동되고, 문제는 첫 요청이 실제 자격 증명을 필요로 하는 순간에야 드러난다. 아래는 Pydantic `BaseSettings`를 이용한 올바른 fail-fast 패턴이며, 이 문서 작성 시점에는 아직 코드에 반영되지 않았다.

---

## Pydantic `BaseSettings` — 관심사별 설정 클래스

```
pydantic-settings
```

관심사별로 설정 클래스를 분리한다. 하나의 거대한 설정 클래스에 모든 값을 담지 않는다.

```python
# src/config/database_config.py
from pydantic_settings import BaseSettings, SettingsConfigDict


class DatabaseConfig(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="DATABASE_")

    url: str   # 필수값 — 기본값 없음. 없으면 인스턴스화 시 ValidationError로 즉시 실패
```

```python
# src/config/jwt_config.py
from pydantic_settings import BaseSettings, SettingsConfigDict


class JwtConfig(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="JWT_")

    secret: str          # 필수값
    expire_minutes: int = 60
```

```python
# src/config/aws_config.py
from pydantic_settings import BaseSettings, SettingsConfigDict


class AwsConfig(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AWS_")

    region: str = "us-east-1"
    endpoint_url: str | None = None       # LocalStack용 — 미설정 시 실제 클라우드 사용
    access_key_id: str = "test"           # 로컬 전용 기본값 — 운영은 Secrets Manager로 주입
    secret_access_key: str = "test"
```

**기본값 설정 원칙:**
- 로컬 개발에서 그대로 동작하는 값(`region`, `expire_minutes`)은 기본값을 둔다.
- 운영에서 빈 값이면 안 되는 항목(`DatabaseConfig.url`, `JwtConfig.secret`)은 **타입에 기본값을 주지 않는다** — Pydantic이 누락 시 `ValidationError`를 던진다.

---

## Fail-Fast — 기동 시 환경 변수 검증

필수 설정이 누락되면 앱 기동 단계에서 즉시 프로세스를 종료한다. 잘못된 설정으로 런타임에 장애가 발생하는 것보다 기동 단계에서 빠르게 실패(fail-fast)하는 것이 훨씬 안전하다.

```python
# src/config/validator.py
import sys

from pydantic import ValidationError

from .database_config import DatabaseConfig
from .jwt_config import JwtConfig


def validate_env() -> None:
    try:
        DatabaseConfig()   # type: ignore[call-arg]  — 값은 환경 변수에서 채워짐
        JwtConfig()         # type: ignore[call-arg]
    except ValidationError as exc:
        print(f"환경 변수 검증 실패:\n{exc}", file=sys.stderr)
        sys.exit(1)   # 즉시 종료
```

`main.py`의 `lifespan` 진입 이전, 모듈 임포트 시점에 호출해 앱이 요청을 받기 전에 실패하도록 한다.

```python
# main.py
from src.config.validator import validate_env

validate_env()   # 실패 시 여기서 프로세스가 종료된다 — 이후 코드는 실행되지 않음

app = FastAPI(title="Account Service", lifespan=lifespan)
```

**`sys.exit(1)`을 쓰는 이유:** 잘못된 설정으로 앱이 기동되면 요청을 처리하다가 예상치 못한 방식으로 실패한다(예: `SES_SENDER_EMAIL`이 빈 문자열이면 발송 시점에야 SES가 거부한다). fail-fast는 배포 파이프라인(healthcheck, readiness)에서 즉시 감지되고, 운영자가 문제를 빠르게 인지할 수 있게 한다.

---

## 민감값 — 환경 변수 vs Secrets Manager

| 항목 | 권장 방식 |
|------|----------|
| 일반 설정 (호스트명, 포트, 타임아웃, region) | 환경 변수 |
| 민감값 (`DATABASE_URL`의 비밀번호, `JWT_SECRET`, AWS 자격 증명) | Secrets Manager |

환경 변수는 컨테이너 로그, 프로세스 목록(`/proc/<pid>/environ`), 오케스트레이터 UI에서 노출될 수 있다. DB 비밀번호, JWT secret 등은 **AWS Secrets Manager**를 사용해 앱 기동 시 주입한다. 로컬 개발에서는 환경 변수/`.env` 그대로 사용하고, 운영에서는 Secrets Manager 조회로 분기한다.

→ 조회·캐싱 구현 상세는 [secret-manager.md](secret-manager.md) 참조.

시크릿을 코드에 하드코딩하거나 `.env`를 커밋하지 않는다. `.env`는 로컬 개발 전용이며 `.gitignore`에 포함한다 (`implementations/fastapi/examples/.gitignore` 참조).

---

## 설정 접근 패턴 — Infrastructure 레이어에서만

설정 값은 Infrastructure 레이어(`infrastructure/`, `database.py`, `main.py`)에서만 직접 참조한다. `application/`, `domain/`은 `os.environ`이나 설정 객체를 직접 import하지 않는다.

```python
# 올바른 방식 — Infrastructure 레이어에서 설정 접근
class SqlAlchemyAccountRepository(AccountRepository):
    def __init__(self, session: AsyncSession) -> None:
        self._session = session   # session은 Infrastructure(database.py)가 설정으로 조립

# 잘못된 방식 — Application Handler가 환경 변수를 직접 참조
class DepositHandler:
    async def execute(self, cmd: DepositCommand) -> Transaction:
        url = os.environ["DATABASE_URL"]   # 금지 — Handler는 설정을 모른다
```

`src/account/infrastructure/notification/notification_service.py`가 `os.getenv("AWS_REGION", ...)` 등을 직접 참조하는 것은 위치(Infrastructure) 자체는 원칙과 일치한다. 다만 fail-fast 검증이 없다는 점, 그리고 Technical Service 구현체 내부에서 산발적으로 `os.getenv`를 호출하는 대신 `AwsConfig` 객체를 주입받는 형태로 정리하면 [secret-manager.md](secret-manager.md)의 캐싱 계층과도 자연스럽게 통합된다.

---

## 원칙

- **Fail-fast**: `pydantic_settings.BaseSettings` 인스턴스화 시점에 필수 환경 변수를 검증하고, 실패하면 `sys.exit(1)`로 즉시 종료한다.
- **관심사별 분리**: `config/database_config.py`, `config/jwt_config.py`, `config/aws_config.py`처럼 설정 클래스를 나눈다.
- **민감값은 Secrets Manager**: 비밀번호, API 키, JWT secret은 운영에서 환경 변수가 아닌 Secrets Manager로 관리한다.
- **설정 접근은 Infrastructure 레이어**: `application/`, `domain/`은 설정에 직접 의존하지 않는다.
- **`.env`는 로컬 전용**: `.gitignore`에 포함, 절대 커밋하지 않는다.

---

### 관련 문서

- [container.md](container.md) — 환경 변수 주입 방법 (컨테이너 실행 시)
- [graceful-shutdown.md](graceful-shutdown.md) — 기동 순서 (`lifespan`과 `validate_env()`의 관계)
- [secret-manager.md](secret-manager.md) — Secrets Manager 조회/캐싱 상세
- [local-dev.md](local-dev.md) — 로컬 개발 환경 구성 (`.env.development`)
