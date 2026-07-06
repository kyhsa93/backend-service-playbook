# Secret 관리

> 프레임워크 무관 원칙: [../../../../docs/architecture/secret-manager.md](../../../../docs/architecture/secret-manager.md)

## 알려진 격차 — 현재 하드코딩된 `"test"` 기본값뿐, Secrets Manager 연동이 없다

`infrastructure/notification/notification_service.py`는 AWS 자격 증명을 이렇게 읽는다.

```python
# 현재 examples/ 코드
aws_access_key_id=os.getenv("AWS_ACCESS_KEY_ID", "test"),
aws_secret_access_key=os.getenv("AWS_SECRET_ACCESS_KEY", "test"),
```

`"test"` 기본값은 LocalStack에서는 유효하지만(LocalStack은 자격 증명을 검증하지 않는다), 프로덕션에서 환경 변수가 설정되지 않으면 **자격 증명이 조용히 `"test"`가 되어 실제 AWS 호출이 인증 실패로 죽는다** — fail-fast가 아니라 fail-silent에 가깝다. DB 비밀번호(`src/database.py`의 `DATABASE_URL`)도 마찬가지로 환경 변수 하나에 통째로 담겨 있고, Secrets Manager 조회 경로가 전혀 없다. 이 문서 작성 시점 기준 이 격차는 코드에 남아 있다.

---

## 올바른 패턴 — SecretService를 Technical Service로 추상화

`application/service/notification_service.py`가 이미 보여준 Technical Service 패턴(Application에 ABC, Infrastructure에 구현체)을 그대로 적용한다.

```python
# application/service/secret_service.py (신설 제안) — 인터페이스
from abc import ABC, abstractmethod


class SecretService(ABC):
    @abstractmethod
    async def get_secret(self, secret_id: str) -> dict: ...
```

```python
# infrastructure/secret/aws_secret_service.py (신설 제안) — Secrets Manager 구현체 + TTL 캐시
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

## 기동 시 설정 객체에 주입

시크릿은 요청마다가 아니라 **앱 기동 시 한 번** 조회해 설정 객체에 담는다. 로컬 개발에서는 환경 변수를, 운영에서는 Secrets Manager를 사용하도록 분기한다.

```python
# src/config/database_config.py — config.md의 DatabaseConfig 확장
import os

from pydantic_settings import BaseSettings, SettingsConfigDict


class DatabaseConfig(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="DATABASE_")
    url: str | None = None   # 로컬은 환경 변수, 운영은 아래 팩토리가 채움


async def load_database_config(secret_service: SecretService) -> DatabaseConfig:
    if os.getenv("APP_ENV") == "production":
        secret = await secret_service.get_secret("app/database")
        return DatabaseConfig(
            url=f"postgresql+asyncpg://{secret['username']}:{secret['password']}@{secret['host']}:{secret['port']}/{secret['dbname']}"
        )
    return DatabaseConfig()   # 로컬 — 환경 변수(DATABASE_URL)에서 그대로 로드
```

`main.py`의 `lifespan` 진입 전, [config.md](config.md)의 `validate_env()`와 같은 시점에 이 로딩을 수행해 시크릿 조회 실패도 기동 단계에서 fail-fast로 드러나게 한다.

---

## 로컬 개발 — LocalStack

```bash
# localstack/init-secrets.sh (신설 제안)
#!/bin/sh
set -e

awslocal secretsmanager create-secret \
  --name app/database \
  --secret-string '{"host":"database","port":"5432","username":"dev","password":"dev","dbname":"app"}'
```

```yaml
# docker-compose.yml — SERVICES에 secretsmanager 추가
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
