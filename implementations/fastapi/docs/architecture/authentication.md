# 인증 패턴

> 프레임워크 무관 원칙: [../../../../docs/architecture/authentication.md](../../../../docs/architecture/authentication.md)

## 현재 구현 — JWT Bearer 인증이 이미 적용되어 있다

`src/account/interface/rest/account_router.py`의 라우터는 `APIRouter(..., dependencies=[Depends(get_current_user)])`로 선언되어 있어, `/accounts` 하위 모든 엔드포인트가 JWT 검증을 거친다. 각 라우트 함수도 `current_user: CurrentUser = Depends(get_current_user)`를 파라미터로 받아 검증된 `user_id`를 Command/Query에 실어 전달한다. 과거에 쓰였던 `x_user_id: str = Header(...)` 같은 무검증 헤더 신뢰 패턴은 더 이상 존재하지 않는다.

아래는 이 인증 흐름의 상세와, 실제 구현체(`src/auth/`)가 따르는 레이어 배치 원칙이다.

---

## 인증 흐름

```
[토큰 발급]
클라이언트 → POST /auth/sign-in (credentials)
           → AuthService: 자격 증명 검증 → Access Token 발급
           → 클라이언트: { "access_token": "..." }

[인증 요청]
클라이언트 → Authorization: Bearer <access_token> 헤더 포함
          → Interface 레이어 (FastAPI Depends 의존성): 토큰 추출 → 검증
          → request.state 또는 Depends 반환값으로 사용자 정보 전달 → 라우터 함수로 전달
```

---

## 레이어 배치 원칙

**인증은 Interface 레이어에서만 처리한다.** Domain 레이어와 Application 레이어(Handler)는 인증 컨텍스트에 의존하지 않는다.

```
Interface 레이어 (interface/rest/):  Depends()로 토큰 추출 → 검증 → owner_id 확보
Application 레이어 (application/command|query/): Command/Query에 owner_id 등 필요한 값만 포함
Domain 레이어 (domain/): 인증 개념 없음. requester_id는 이미 검증된 값으로 전달받음
```

잘못된 패턴 — Handler(Application 레이어)에서 토큰 직접 검증:

```python
# 금지 — Handler에서 토큰을 직접 디코딩
class DepositHandler:
    async def execute(self, token: str, cmd: DepositCommand) -> Transaction:
        payload = jwt.decode(token, SECRET, algorithms=["HS256"])  # ← Interface 레이어 역할
        ...
```

올바른 패턴 — `Depends()`가 토큰을 검증하고 `owner_id`만 라우터에 전달, 라우터가 Command에 실어 Handler로 넘긴다:

```python
@router.post("/{account_id}/deposit", ...)
async def deposit(
    account_id: str,
    body: DepositRequest,
    current_user: CurrentUser = Depends(get_current_user),   # 검증 완료된 사용자만 도달
    repo: SqlAlchemyAccountRepository = Depends(_repo),
    outbox_relay: OutboxRelay = Depends(_outbox_relay),
) -> TransactionResponse:
    transaction = await DepositHandler(repo, outbox_relay).execute(
        DepositCommand(account_id=account_id, requester_id=current_user.user_id, amount=body.amount)
    )
    ...
```

---

## JWT Bearer 토큰 패턴 — `python-jose` 기반 구현

### 의존성

```
python-jose[cryptography]
```

### 토큰 payload 모델과 발급

```python
# src/auth/domain/token.py — JWT payload 모델 (최소한의 정보만 포함)
from pydantic import BaseModel


class TokenPayload(BaseModel):
    user_id: str
    exp: int
```

```python
# src/auth/application/service/auth_service.py — 인터페이스 (Technical Service)
from abc import ABC, abstractmethod


class AuthService(ABC):

    @abstractmethod
    def issue_token(self, user_id: str) -> str: ...

    @abstractmethod
    def verify_token(self, token: str) -> str:
        """검증 성공 시 user_id를 반환하고, 실패 시 InvalidTokenError를 raise한다."""
```

```python
# src/auth/infrastructure/jwt_auth_service.py — 실제 코드
import os
from datetime import datetime, timedelta, timezone

from jose import JWTError, jwt

from ..application.service.auth_service import AuthService
from ..domain.errors import InvalidTokenError

JWT_ALGORITHM = "HS256"
ACCESS_TOKEN_TTL = timedelta(hours=1)

# 프로덕션에서는 main.py의 lifespan 기동 시 Secrets Manager에서 조회한 값으로
# set_jwt_secret()이 이 값을 채운다. 그 전까지는 환경 변수를 쓴다 — validate_env()가
# 프로덕션이 아닌 환경에서 JWT_SECRET 누락을 이미 fail-fast로 막았으므로 조용한
# 기본값("dev-secret")을 두지 않는다.
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
```

**`JWT_SECRET`은 이미 fail-fast 검증된다.** [config.md](config.md)의 `validate_env()`가 `JwtConfig()`를 인스턴스화하고, `APP_ENV != "production"`인데 `secret`이 비어 있으면 `sys.exit(1)`로 즉시 종료한다. 프로덕션에서는 `main.py`의 `lifespan`이 Secrets Manager(`app/jwt`)에서 조회해 `set_jwt_secret()`으로 채우므로([secret-manager.md](secret-manager.md) 참고) `JWT_SECRET` 환경 변수가 없어도 된다 — 그래서 `validate_env()`는 프로덕션에서 이 검증을 건너뛴다.

### FastAPI `Depends` 기반 인증 의존성

```python
# src/auth/interface/rest/dependencies.py
from dataclasses import dataclass

from fastapi import Depends
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from ...infrastructure.jwt_auth_service import JwtAuthService

_bearer_scheme = HTTPBearer()


@dataclass(frozen=True)
class CurrentUser:
    user_id: str


def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(_bearer_scheme),
) -> CurrentUser:
    auth_service = JwtAuthService()
    user_id = auth_service.verify_token(credentials.credentials)
    return CurrentUser(user_id=user_id)
```

`fastapi.security.HTTPBearer`가 `Authorization: Bearer <token>` 헤더 파싱과 "헤더 자체가 없음" 케이스(401)를 대신 처리해준다. `get_current_user`는 그 위에서 서명·만료만 검증한다.

### 라우터에 적용

```python
# src/account/interface/rest/account_router.py — 실제 코드
from ....auth.interface.rest.dependencies import CurrentUser, get_current_user

@router.post("/{account_id}/deposit", status_code=201, response_model=TransactionResponse)
async def deposit(
    account_id: str,
    body: DepositRequest,
    current_user: CurrentUser = Depends(get_current_user),   # 검증 완료된 사용자만 도달
    repo: SqlAlchemyAccountRepository = Depends(_repo),
    outbox_relay: OutboxRelay = Depends(_outbox_relay),
) -> TransactionResponse:
    transaction = await DepositHandler(repo, outbox_relay).execute(
        DepositCommand(account_id=account_id, requester_id=current_user.user_id, amount=body.amount)
    )
    ...
```

`APIRouter(dependencies=[Depends(get_current_user)])`로 라우터 단위에 걸면 개별 엔드포인트마다 반복 선언할 필요가 없다.

```python
router = APIRouter(prefix="/accounts", tags=["Account"], dependencies=[Depends(get_current_user)])
```

**라우터(클래스에 준하는 단위) 레벨에 거는 이유:** 엔드포인트별로 개별 적용하면 새 엔드포인트 추가 시 인증 적용을 누락할 위험이 있다. `POST /auth/sign-in`, `GET /health/*`처럼 인증이 불필요한 엔드포인트만 별도 라우터로 분리해 `dependencies`를 걸지 않는다.

---

## 토큰 payload 설계

JWT payload에는 **최소한의 정보**만 담는다.

```python
# 올바른 방식 — user_id만 포함
{"user_id": "owner-1", "exp": 1234571490}

# 잘못된 방식 — 민감 정보 또는 자주 변하는 정보 포함
{"user_id": "...", "email": "...", "role": "...", "permissions": [...]}
```

**이유:**
- JWT payload는 서명만 되고 암호화되지 않는다 (base64 디코딩으로 누구나 읽을 수 있다).
- 역할/권한은 발급 후 변경될 수 있다. 토큰에 담으면 변경이 즉시 반영되지 않는다.
- 추가 사용자 정보가 필요하면 요청 처리 시점에 DB에서 조회한다.

---

## 테스트에서의 인증 우회

E2E 테스트(`tests/test_account_e2e.py`)는 `app.dependency_overrides[get_current_user]`로 인증 의존성을 고정된 테스트 사용자로 치환한다 — 실제 토큰 발급 없이 테스트를 실행할 수 있다.

```python
app.dependency_overrides[get_current_user] = lambda: CurrentUser(user_id=OWNER_ID)
```

→ 상세 테스트 전략은 [testing.md](testing.md) 참조.

---

### 관련 문서

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — 요청 파이프라인에서 인증 위치
- [layer-architecture.md](layer-architecture.md) — Interface 레이어 역할
- [config.md](config.md) — 환경 변수 관리, `JWT_SECRET` fail-fast 검증(아직 미구현 — 알려진 격차)
