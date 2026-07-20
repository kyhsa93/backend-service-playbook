# 인증 패턴

> 프레임워크 무관 원칙: [../../../../docs/architecture/authentication.md](../../../../docs/architecture/authentication.md)

## 현재 구현 — JWT Bearer 인증 + 비밀번호 기반 자격 증명 검증이 적용되어 있다

`src/account/interface/rest/account_router.py`의 라우터는 `APIRouter(..., dependencies=[Depends(get_current_user)])`로 선언되어 있어, `/accounts` 하위 모든 엔드포인트가 JWT 검증을 거친다. 각 라우트 함수도 `current_user: CurrentUser = Depends(get_current_user)`를 파라미터로 받아 검증된 `user_id`를 Command/Query에 실어 전달한다.

`POST /auth/sign-in`도 실제 비밀번호 검증을 거친다. `Credential` Aggregate(bcrypt 해시)와 `PasswordHasher` Technical Service를 통해 저장된 해시와 비교한 뒤에만 토큰을 발급한다.

아래는 이 인증 흐름의 상세와, 실제 구현체(`src/auth/`)가 따르는 레이어 배치 원칙이다.

---

## 인증 흐름

```
[가입]
클라이언트 → POST /auth/sign-up { user_id, password }
           → SignUpHandler: user_id 중복 확인 → PasswordHasher로 비밀번호 해싱 → Credential 저장
           → 클라이언트: 201

[토큰 발급]
클라이언트 → POST /auth/sign-in { user_id, password }
           → SignInHandler: CredentialRepository로 저장된 해시 조회 → PasswordHasher.verify()로 비밀번호 검증
           → 검증 성공 시 AuthService.issue_token()으로 Access Token 발급
           → 클라이언트: { "access_token": "..." }

[인증 요청]
클라이언트 → Authorization: Bearer <access_token> 헤더 포함
          → Interface 레이어 (FastAPI Depends 의존성): 토큰 추출 → 검증
          → request.state 또는 Depends 반환값으로 사용자 정보 전달 → 라우터 함수로 전달
```

**user_id 미존재와 비밀번호 불일치는 동일한 에러(`INVALID_CREDENTIALS`, 401)로 응답한다** — 둘을 구분해서 응답하면 공격자가 존재하는 아이디를 추측할 수 있다(user enumeration).

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
) -> TransactionResponse:
    transaction = await DepositHandler(repo).execute(
        DepositCommand(account_id=account_id, requester_id=current_user.user_id, amount=body.amount)
    )
    ...
```

---

## JWT Bearer 토큰 패턴 — `python-jose` 기반 구현

### 의존성

```
python-jose[cryptography]
bcrypt
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

---

## Credential — 비밀번호 검증

`AuthService`(JWT 발급/검증)는 "이미 인증된 사용자"를 전제로 토큰만 다룬다. "이 user_id와 비밀번호가 실제로 일치하는가"를 판단하는 것은 별도 관심사이므로, `Credential` Aggregate + `CredentialRepository` + `PasswordHasher` Technical Service로 분리했다.

```python
# src/auth/domain/credential.py — Credential Aggregate
class Credential:
    def __init__(self, credential_id: str, user_id: str, password_hash: str, created_at: datetime) -> None:
        self.credential_id = credential_id
        self.user_id = user_id
        self.password_hash = password_hash  # 평문 비밀번호는 domain/application 어디에도 보관하지 않는다
        self.created_at = created_at

    @classmethod
    def create(cls, user_id: str, password_hash: str) -> Credential:
        return cls(credential_id=generate_id(), user_id=user_id, password_hash=password_hash,
                    created_at=datetime.utcnow())
```

```python
# src/auth/domain/repository.py — Repository ABC
class CredentialRepository(ABC):
    @abstractmethod
    async def find_by_user_id(self, user_id: str) -> Credential | None: ...

    @abstractmethod
    async def save(self, credential: Credential) -> None: ...
```

```python
# src/auth/application/service/password_hasher.py — Technical Service ABC
class PasswordHasher(ABC):
    @abstractmethod
    async def hash(self, plain_password: str) -> str: ...

    @abstractmethod
    async def verify(self, plain_password: str, password_hash: str) -> bool: ...
```

```python
# src/auth/infrastructure/security/bcrypt_password_hasher.py — 구현체
class BcryptPasswordHasher(PasswordHasher):
    # bcrypt.hashpw/checkpw는 동기·CPU-bound(salt round 12 기준 수백ms)라 이벤트 루프를 막지
    # 않도록 asyncio.to_thread로 워커 스레드에서 실행한다.
    async def hash(self, plain_password: str) -> str:
        return await asyncio.to_thread(self._hash_sync, plain_password)

    async def verify(self, plain_password: str, password_hash: str) -> bool:
        return await asyncio.to_thread(self._verify_sync, plain_password, password_hash)
```

비밀번호 해싱은 이메일 발송(`NotificationService`)과 동일한 Technical Service 패턴이다 — `application/service/`에 ABC, `infrastructure/<concern>/`에 구현체를 두어 Domain/Application이 `bcrypt` 같은 구체 라이브러리에 의존하지 않게 한다. `AuthService`(토큰 발급/검증)는 동기 메서드이지만, `PasswordHasher`는 CPU-bound 작업을 스레드로 위임해야 하므로 async로 선언했다.

### SignUpHandler / SignInHandler

`application/command/`에 위치하는 일반 Handler다(다른 도메인과 동일하게 별도 Command Bus 없이 라우터가 직접 생성해서 호출한다).

```python
# src/auth/application/command/sign_up_handler.py
class SignUpHandler:
    def __init__(self, repo: CredentialRepository, password_hasher: PasswordHasher) -> None:
        self._repo = repo
        self._password_hasher = password_hasher

    async def execute(self, cmd: SignUpCommand) -> None:
        existing = await self._repo.find_by_user_id(cmd.user_id)
        if existing is not None:
            raise UserIdAlreadyExistsError()

        password_hash = await self._password_hasher.hash(cmd.password)
        credential = Credential.create(user_id=cmd.user_id, password_hash=password_hash)
        await self._repo.save(credential)
```

```python
# src/auth/application/command/sign_in_handler.py
class SignInHandler:
    def __init__(self, repo: CredentialRepository, password_hasher: PasswordHasher, auth_service: AuthService) -> None:
        self._repo = repo
        self._password_hasher = password_hasher
        self._auth_service = auth_service

    async def execute(self, cmd: SignInCommand) -> str:
        credential = await self._repo.find_by_user_id(cmd.user_id)
        # user_id 미존재/비밀번호 불일치를 동일한 에러로 응답 — user enumeration 방지
        if credential is None:
            raise InvalidCredentialsError()

        is_valid = await self._password_hasher.verify(cmd.password, credential.password_hash)
        if not is_valid:
            raise InvalidCredentialsError()

        return self._auth_service.issue_token(credential.user_id)
```

`InvalidCredentialsError`는 `main.py`의 `@app.exception_handler`에서 401로, `UserIdAlreadyExistsError`는 400으로 매핑된다(둘 다 `AuthErrorCode`를 `code` 필드에 담아 4필드 에러 응답 형식을 따른다 — [error-handling.md](error-handling.md) 참고).

### 디렉토리 구조

```
src/auth/
  domain/
    credential.py              ← Credential Aggregate
    repository.py              ← CredentialRepository(ABC)
    token.py                   ← JWT payload 모델
    errors.py                  ← InvalidTokenError, InvalidCredentialsError, UserIdAlreadyExistsError
    error_codes.py             ← AuthErrorCode
  application/
    command/
      sign_up_handler.py       ← user_id 중복 확인 → 해싱 → 저장
      sign_in_handler.py       ← 해시 조회 → 검증 → 토큰 발급
    service/
      auth_service.py          ← AuthService(ABC) — 토큰 발급/검증
      password_hasher.py       ← PasswordHasher(ABC) — 비밀번호 해싱/검증 (Technical Service)
  infrastructure/
    jwt_auth_service.py        ← AuthService 구현체 (python-jose)
    security/
      bcrypt_password_hasher.py ← PasswordHasher 구현체 (bcrypt)
    persistence/
      credential_repository.py ← CredentialRepository 구현체 (SQLAlchemy)
  interface/
    rest/
      auth_router.py           ← POST /auth/sign-up, POST /auth/sign-in
      dependencies.py          ← get_current_user (JWT 검증 Depends)
      schemas.py                ← SignUpRequest, SignInRequest, SignInResponse
```

---

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
) -> TransactionResponse:
    transaction = await DepositHandler(repo).execute(
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

Account/Card/Notification E2E 테스트(`tests/test_account_e2e.py` 등)는 `JwtAuthService().issue_token(user_id)`로 토큰을 직접 발급해 `Authorization` 헤더에 실어 보낸다 — `POST /auth/sign-up`/`POST /auth/sign-in`을 거치지 않고도 임의의 `user_id`로 서명된 유효한 토큰을 만들 수 있다(비밀번호 검증은 sign-in 엔드포인트에만 있고, JWT 서명/검증 자체는 `user_id`만 있으면 되기 때문). 이 도메인들의 관심사는 "인증된 사용자로서의 계좌/카드 동작"이지 "로그인 절차 자체"가 아니므로, sign-up/sign-in 실호출 없이 테스트 사용자를 준비하는 편이 더 빠르고 단순하다.

```python
def auth_headers(user_id: str) -> dict:
    token = JwtAuthService().issue_token(user_id)
    return {"Authorization": f"Bearer {token}"}
```

`POST /auth/sign-up`/`POST /auth/sign-in` 자체의 동작(가입 성공, 비밀번호 불일치, 존재하지 않는 아이디, 아이디 중복, 비밀번호 검증 실패)은 별도의 `tests/test_auth_e2e.py`가 실제 HTTP 요청으로 검증한다 — 이 테스트만 sign-up/sign-in을 실제로 호출한다.

→ 상세 테스트 전략은 [testing.md](testing.md) 참조.

---

### 관련 문서

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — 요청 파이프라인에서 인증 위치
- [layer-architecture.md](layer-architecture.md) — Interface 레이어 역할
- [config.md](config.md) — 환경 변수 관리, `JWT_SECRET` fail-fast 검증
