# Secret 관리

> 프레임워크 무관 원칙: [../../../../docs/architecture/secret-manager.md](../../../../docs/architecture/secret-manager.md)

## 현재 구현 — `SecretService`/`AwsSecretService`는 이미 존재한다 (Secrets Manager는 JWT secret에만 연동됨)

`src/common/secret_service.py`(ABC)와 `src/common/aws_secret_service.py`(TTL 캐시를 가진 Secrets Manager 구현체)가 이미 실제 코드로 존재한다. `main.py`의 `lifespan`이 `APP_ENV == "production"`일 때만 `AwsSecretService().get_secret("app/jwt")`를 호출해 JWT secret을 조회하고 `set_jwt_secret()`으로 주입한다.

다만 **DB 자격 증명과 AWS 자격 증명 자체는 여전히 Secrets Manager를 거치지 않는다.**

```python
# src/common/aws_secret_service.py — 실제 코드. 이 클라이언트 자체가 AWS 자격 증명을
# os.getenv(..., "test")로 읽는다 — Secrets Manager를 "호출하기 위한" 자격 증명이
# 아직 fail-fast 검증되지 않는다는 뜻이다 (config.md 참조, 별도 추적 중인 격차)
aws_access_key_id=os.getenv("AWS_ACCESS_KEY_ID", "test"),
aws_secret_access_key=os.getenv("AWS_SECRET_ACCESS_KEY", "test"),
```

`"test"` 기본값은 LocalStack에서는 유효하지만(LocalStack은 자격 증명을 검증하지 않는다), 프로덕션에서 환경 변수가 설정되지 않으면 **자격 증명이 조용히 `"test"`가 되어 실제 AWS 호출이 인증 실패로 죽는다** — fail-fast가 아니라 fail-silent에 가깝다. 이 격차는 별도로 추적 중이며(`config.md`의 `AwsConfig`/`aws_config.py` 부재 참조), 이 문서에서 해결 완료로 표시하지 않는다.

또한 DB 비밀번호(`DatabaseConfig.url` — [config.md](config.md) 참조)는 여전히 환경 변수 하나에 통째로 담겨 있고, Secrets Manager 조회 경로가 없다 — 아래 "DB 설정에 Secrets Manager 적용" 절 참조.

---

## 실제 구현 — `SecretService`를 Technical Service로 추상화

`application/service/notification_service.py`가 보여준 Technical Service 패턴(Application에 ABC, Infrastructure에 구현체)과 같은 정신을 따르지만, 실제 배치 위치는 도메인별 `application/service/`·`infrastructure/`가 아니라 **여러 도메인이 공유하는 `src/common/`**이다 — Secret 조회는 Account 도메인에 속하지 않는 순수 인프라 관심사이기 때문이다([shared-modules.md](shared-modules.md) 참조).

```python
# src/common/secret_service.py — 실제 코드. application/service/가 아니라 common/에 있다
from abc import ABC, abstractmethod


class SecretService(ABC):
    @abstractmethod
    async def get_secret(self, secret_id: str) -> dict: ...
```

```python
# src/common/aws_secret_service.py — 실제 코드. infrastructure/secret/이 아니라 common/에 있다
import json
import os
import time

import aioboto3


class AwsSecretService(SecretService):
    def __init__(self, ttl_seconds: int = 300) -> None:
        self._boto_session = aioboto3.Session()
        self._ttl_seconds = ttl_seconds
        self._cache: dict[str, tuple[dict, float]] = {}

    async def get_secret(self, secret_id: str) -> dict:
        cached = self._cache.get(secret_id)
        if cached is not None:
            value, expires_at = cached
            if expires_at > time.monotonic():
                return value

        async with self._boto_session.client(
            "secretsmanager",
            region_name=os.getenv("AWS_REGION", "us-east-1"),
            endpoint_url=os.getenv("AWS_ENDPOINT_URL") or None,   # 로컬은 LocalStack
            aws_access_key_id=os.getenv("AWS_ACCESS_KEY_ID", "test"),
            aws_secret_access_key=os.getenv("AWS_SECRET_ACCESS_KEY", "test"),
        ) as client:
            response = await client.get_secret_value(SecretId=secret_id)

        value = json.loads(response["SecretString"])
        self._cache[secret_id] = (value, time.monotonic() + self._ttl_seconds)
        return value
```

- **TTL 캐시**: 동일 `secret_id`를 5분 내에 다시 조회하면 API 호출 없이 캐시를 반환한다. Secrets Manager는 호출당 과금되므로 요청마다 조회하지 않는다.
- **JSON 형태로 저장**: DB 접속 정보처럼 논리적으로 묶이는 값은 하나의 시크릿에 JSON으로 저장해 API 호출 수를 줄인다.
- **엔드포인트 분기**: `AWS_ENDPOINT_URL`이 설정되면 LocalStack, 없으면 실제 Secrets Manager — `notification_service.py`가 이미 쓰는 패턴과 동일하다.

---

## 기동 시 주입 — 실제로는 JWT secret에만 적용됨

시크릿은 요청마다가 아니라 **앱 기동 시 한 번** 조회한다. 실제 코드에서 이 패턴이 적용된 것은 JWT secret 하나뿐이다.

```python
# main.py — 실제 코드
@asynccontextmanager
async def lifespan(app: FastAPI):
    # 프로덕션에서만 Secrets Manager를 호출한다 — 그 외(로컬/테스트 기본값)는
    # 네트워크 호출 없이 환경 변수(JWT_SECRET)만 사용한다.
    if os.getenv("APP_ENV") == "production":
        secret = await AwsSecretService().get_secret("app/jwt")
        set_jwt_secret(secret["secret"])
    yield
```

**DB 설정에는 이 패턴이 적용되지 않았다 — 여전히 순수 환경 변수다.** [config.md](config.md)의 실제 `DatabaseConfig`는 다음과 같다.

```python
# src/config/database_config.py — 실제 코드
class DatabaseConfig(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="DATABASE_")
    url: str   # 필수값 — Secrets Manager 경로 없음, 환경 변수(DATABASE_URL)에서만 채워진다
```

아래는 DB 접속 정보를 Secrets Manager에서 조회해 `DatabaseConfig`를 구성하는 확장 제안이다 — **아직 구현되지 않았다.** 실제로 구현한다면 `url`을 필수 `str`로 유지한 채 아래처럼 별도 팩토리 함수로 조합해야 한다(위 실제 `DatabaseConfig`의 `url: str` 정의와 모순되지 않도록, `url` 자체를 옵셔널로 바꾸는 대신 팩토리가 채워서 넘긴다).

```python
# (신설 제안, 아직 구현되지 않음) — DatabaseConfig.url은 필수값 그대로 두고, 값 자체를
# Secrets Manager에서 만들어 생성자에 넘긴다
async def load_database_config(secret_service: SecretService) -> DatabaseConfig:
    if os.getenv("APP_ENV") == "production":
        secret = await secret_service.get_secret("app/database")
        return DatabaseConfig(
            url=f"postgresql+asyncpg://{secret['username']}:{secret['password']}@{secret['host']}:{secret['port']}/{secret['dbname']}"
        )
    return DatabaseConfig()   # 로컬 — 환경 변수(DATABASE_URL)에서 그대로 로드
```

`main.py`의 `validate_env()`(모듈 임포트 시점 — [graceful-shutdown.md](graceful-shutdown.md) 참조)와 같은 시점에 이 로딩을 수행하면 시크릿 조회 실패도 기동 단계에서 fail-fast로 드러난다.

---

## 로컬 개발 — LocalStack

```bash
# localstack/init-secrets.sh — 실제 코드. app/database가 아니라 app/jwt를 생성한다
#!/bin/sh
set -e

awslocal secretsmanager create-secret \
  --name app/jwt \
  --secret-string '{"secret":"local-dev-secret"}'
```

```yaml
# docker-compose.yml — 실제 코드. SERVICES에 secretsmanager가 이미 포함되어 있다
localstack:
  image: localstack/localstack:3.0
  environment:
    SERVICES: ses,secretsmanager
```

→ 전체 LocalStack 구성은 [local-dev.md](local-dev.md) 참조.

---

## 원칙

- **민감값은 환경 변수에 직접 넣지 않는다**: 운영에서는 Secrets Manager에서 조회한다. `"test"` 같은 하드코딩된 기본값은 로컬 전용이며, 운영 미설정 시 fail-fast로 이어져야 한다(무음 실패 금지).
- **TTL 캐시를 적용한다**: Secrets Manager 호출은 과금/rate limit이 있다.
- **SecretService ABC로 추상화한다**: `notification_service.py`와 동일한 Technical Service 구조.
- **논리적으로 묶이는 값은 하나의 시크릿에 JSON으로 저장한다**.
- **로컬 개발은 LocalStack**: 실제 Secrets Manager에 접근하지 않는다.

### 관련 문서

- [config.md](config.md) — 환경 변수 vs Secrets Manager 사용 기준, fail-fast 검증
- [layer-architecture.md](layer-architecture.md) — Technical Service 패턴
- [local-dev.md](local-dev.md) — LocalStack 기반 로컬 개발 환경
