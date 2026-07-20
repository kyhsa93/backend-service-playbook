# 환경 설정 관리

> 프레임워크 무관 원칙: [../../../../docs/architecture/config.md](../../../../docs/architecture/config.md)

## 현재 구현 — `DatabaseConfig`/`JwtConfig`/`AwsConfig` 모두 실제 코드

`src/config/database_config.py`의 `DatabaseConfig`(`pydantic_settings.BaseSettings`)가 `DATABASE_URL`을 필수값으로 검증하고, `src/config/validator.py`의 `validate_env()`가 `main.py` 모듈 임포트 시점에 이를 호출해 누락 시 `sys.exit(1)`로 즉시 종료한다 — 아래 "Fail-Fast" 절이 이 실제 코드를 그대로 보여준다.

`src/config/jwt_config.py`의 `JwtConfig`도 같은 방식으로 검증된다. 다만 `JWT_SECRET`은 **프로덕션에서만 예외**다 — 프로덕션에서는 `main.py`의 `lifespan`이 Secrets Manager(`app/jwt`)에서 조회해 채우므로([secret-manager.md](secret-manager.md) 참고) 환경 변수가 비어 있어도 fail-fast 대상이 아니다. `validate_env()`가 `APP_ENV != "production"`일 때만 `JwtConfig.secret` 누락을 fail-fast로 막는다.

**AWS 자격 증명**(`AWS_REGION`/`AWS_ENDPOINT_URL`/`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`)도 `src/config/aws_config.py`의 `AwsConfig`로 캡슐화되어 있다. `src/common/aws_secret_service.py`와 `src/account/infrastructure/notification/notification_service.py` 모두 `AwsConfig().client_kwargs()`로 boto3 클라이언트를 생성한다 — AWS 자격 증명 접근은 각자 `os.getenv(..., "test")`를 호출하는 대신 `AwsConfig` 하나로 중앙화되어 있다. 단, `AwsConfig`는 모든 필드에 로컬 개발용 기본값(`region="us-east-1"`, `access_key_id="test"` 등)이 있어 **fail-fast 대상은 아니다** — AWS 자격 증명은 운영에서 IAM 역할로 대체되는 것이 일반적이라, DB 접속 정보/JWT secret과 달리 "누락 시 종료해야 하는 값"으로 보지 않는다(다른 언어 구현체도 SES/AWS 자격 증명 자체는 fail-fast 대상에서 제외한다).

아래는 Pydantic `BaseSettings`를 이용한 fail-fast 패턴이다. `DatabaseConfig`/`JwtConfig`/`AwsConfig` 모두 실제 코드다.

---

## Pydantic `BaseSettings` — 관심사별 설정 클래스

```
pydantic-settings
```

관심사별로 설정 클래스를 분리한다. 하나의 거대한 설정 클래스에 모든 값을 담지 않는다.

```python
# src/config/database_config.py — 실제 코드
from pydantic_settings import BaseSettings, SettingsConfigDict


class DatabaseConfig(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="DATABASE_")

    url: str   # 필수값 — 기본값 없음. 없으면 인스턴스화 시 ValidationError로 즉시 실패
```

```python
# src/config/jwt_config.py — 실제 코드
from pydantic_settings import BaseSettings, SettingsConfigDict


class JwtConfig(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="JWT_")

    # 기본값 없음 — 그러나 프로덕션에서는 Secrets Manager가 대신 채우므로
    # 타입 자체는 Optional이고, "필수 여부" 판단은 validate_env()가 한다(아래 참고).
    secret: str | None = None
```

```python
# src/config/aws_config.py — 실제 코드
from pydantic_settings import BaseSettings, SettingsConfigDict


class AwsConfig(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AWS_")

    region: str = "us-east-1"
    endpoint_url: str | None = None  # LocalStack용 — 미설정 시 실제 클라우드 사용
    access_key_id: str = "test"  # 로컬 전용 기본값 — 운영은 IAM 역할/Secrets Manager로 대체
    secret_access_key: str = "test"

    def client_kwargs(self) -> dict[str, str | None]:
        return {
            "region_name": self.region,
            "endpoint_url": self.endpoint_url,
            "aws_access_key_id": self.access_key_id,
            "aws_secret_access_key": self.secret_access_key,
        }
```

`client_kwargs()`는 `aioboto3.Session().client(...)`에 그대로 `**`로 펼쳐 넣을 수 있는 형태다 — `src/common/aws_secret_service.py`/`src/account/infrastructure/notification/notification_service.py` 모두 이 메서드로 boto3 클라이언트를 만든다.

현재 `src/config/`에는 `database_config.py`, `jwt_config.py`, `aws_config.py`, `validator.py`가 있다.

**기본값 설정 원칙:**
- 로컬 개발에서 그대로 동작하는 값(`region`)은 기본값을 둔다.
- 운영에서 빈 값이면 안 되는 항목(`DatabaseConfig.url`)은 **타입에 기본값을 주지 않는다** — Pydantic이 누락 시 `ValidationError`를 던진다.
- `JwtConfig.secret`은 예외적으로 타입은 `str | None`이지만, "필수 여부"는 환경(`APP_ENV`)에 따라 달라지므로 Pydantic 타입 시스템이 아니라 `validate_env()`의 명시적 분기로 강제한다 — 프로덕션에서는 Secrets Manager가 값을 채우기 때문에 타입 자체를 필수로 만들 수 없다.

---

## Fail-Fast — 기동 시 환경 변수 검증

필수 설정이 누락되면 앱 기동 단계에서 즉시 프로세스를 종료한다. 잘못된 설정으로 런타임에 장애가 발생하는 것보다 기동 단계에서 빠르게 실패(fail-fast)하는 것이 훨씬 안전하다.

```python
# src/config/validator.py — 실제 코드
import os
import sys

from pydantic import ValidationError

from .database_config import DatabaseConfig
from .jwt_config import JwtConfig


def validate_env() -> None:
    try:
        DatabaseConfig()   # type: ignore[call-arg]  — 값은 환경 변수에서 채워짐
        jwt_config = JwtConfig()   # type: ignore[call-arg]
    except ValidationError as exc:
        print(f"환경 변수 검증 실패:\n{exc}", file=sys.stderr)
        sys.exit(1)   # 즉시 종료

    # 프로덕션은 lifespan 기동 시 Secrets Manager(app/jwt)가 secret을 채우므로
    # JWT_SECRET 환경 변수가 없어도 된다 — 그 외 환경에서는 명시적으로 필요하다.
    if os.getenv("APP_ENV") != "production" and not jwt_config.secret:
        print("환경 변수 검증 실패:\nJWT_SECRET이 설정되어 있지 않습니다.", file=sys.stderr)
        sys.exit(1)
```

**`JwtConfig.secret`이 프로덕션에서는 필수가 아닌 이유**: Pydantic 필드 타입만으로는 "환경에 따라 필수 여부가 달라진다"를 표현할 수 없다 — 그래서 `secret: str | None = None`으로 두고, `validate_env()`가 `APP_ENV`를 직접 확인해 프로덕션이 아닐 때만 fail-fast한다.

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

`src/account/infrastructure/notification/notification_service.py`와 `src/common/aws_secret_service.py`는 각자 `AwsConfig()`를 인스턴스화해 `client_kwargs()`로 boto3 클라이언트를 구성한다 — 위치(Infrastructure)도 올바르고, `os.getenv`를 산발적으로 호출하는 대신 `AwsConfig`를 통해 구성한다.

`domain/`·`application/`이 `os.environ`/`os.getenv`를 직접 호출하는지는 harness의 `no-direct-env-access-outside-config` 규칙이 검사한다.

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
