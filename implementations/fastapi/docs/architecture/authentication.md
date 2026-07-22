# Authentication Pattern

> Framework-agnostic principles: [../../../../docs/architecture/authentication.md](../../../../docs/architecture/authentication.md)

## Current implementation — JWT Bearer authentication + password-based credential verification are in place

The router in `src/account/interface/rest/account_router.py` is declared with `APIRouter(..., dependencies=[Depends(get_current_user)])`, so every endpoint under `/accounts` goes through JWT verification. Each route function also takes `current_user: CurrentUser = Depends(get_current_user)` as a parameter, carrying the verified `user_id` through to the Command/Query.

`POST /auth/sign-in` also goes through actual password verification. It issues a token only after comparing it against the stored hash via the `Credential` Aggregate (a bcrypt hash) and the `PasswordHasher` Technical Service.

Below are the details of this authentication flow, and the layer-placement principles the actual implementation (`src/auth/`) follows.

---

## Authentication flow

```
[Sign-up]
Client → POST /auth/sign-up { user_id, password }
           → SignUpHandler: checks for a duplicate user_id → hashes the password via PasswordHasher → saves the Credential
           → Client: 201

[Token issuance]
Client → POST /auth/sign-in { user_id, password }
           → SignInHandler: looks up the stored hash via CredentialRepository → verifies the password via PasswordHasher.verify()
           → on successful verification, issues an Access Token via AuthService.issue_token()
           → Client: { "access_token": "..." }

[Authenticated request]
Client → includes an Authorization: Bearer <access_token> header
          → Interface layer (a FastAPI Depends dependency): extracts the token → verifies it
          → passes user info via request.state or the Depends return value → passed to the router function
```

**A missing user_id and a password mismatch respond with the same error (`INVALID_CREDENTIALS`, 401)** — responding differently for each would let an attacker guess which usernames exist (user enumeration).

---

## Layer-placement principles

**Authentication is handled only in the Interface layer.** The Domain layer and the Application layer (Handlers) never depend on the authentication context.

```
Interface layer (interface/rest/):  Depends() extracts the token → verifies it → obtains owner_id
Application layer (application/command|query/): the Command/Query includes only the values it needs, such as owner_id
Domain layer (domain/): has no concept of authentication. requester_id is passed in as an already-verified value
```

An incorrect pattern — verifying the token directly in a Handler (the Application layer):

```python
# forbidden — decoding the token directly inside a Handler
class DepositHandler:
    async def execute(self, token: str, cmd: DepositCommand) -> Transaction:
        payload = jwt.decode(token, SECRET, algorithms=["HS256"])  # ← this is the Interface layer's job
        ...
```

The correct pattern — `Depends()` verifies the token and passes only `owner_id` to the router, and the router carries it into the Command handed to the Handler:

```python
@router.post("/{account_id}/deposit", ...)
async def deposit(
    account_id: str,
    body: DepositRequest,
    current_user: CurrentUser = Depends(get_current_user),   # only a verified user reaches here
    repo: SqlAlchemyAccountRepository = Depends(_repo),
) -> TransactionResponse:
    transaction = await DepositHandler(repo).execute(
        DepositCommand(account_id=account_id, requester_id=current_user.user_id, amount=body.amount)
    )
    ...
```

---

## The JWT Bearer token pattern — a `python-jose`-based implementation

### Dependencies

```
python-jose[cryptography]
bcrypt
```

### The token payload model and issuance

```python
# src/auth/domain/token.py — the JWT payload model (contains only the minimal information)
from pydantic import BaseModel


class TokenPayload(BaseModel):
    user_id: str
    exp: int
```

```python
# src/auth/application/service/auth_service.py — the interface (Technical Service)
from abc import ABC, abstractmethod


class AuthService(ABC):

    @abstractmethod
    def issue_token(self, user_id: str) -> str: ...

    @abstractmethod
    def verify_token(self, token: str) -> str:
        """Returns user_id on successful verification, and raises InvalidTokenError on failure."""
```

```python
# src/auth/infrastructure/jwt_auth_service.py — actual code
import os
from datetime import datetime, timedelta, timezone

from jose import JWTError, jwt

from ..application.service.auth_service import AuthService
from ..domain.errors import InvalidTokenError

JWT_ALGORITHM = "HS256"
ACCESS_TOKEN_TTL = timedelta(hours=1)

# In production, set_jwt_secret() fills in this value from what's looked up in Secrets
# Manager at main.py's lifespan startup. Until then, the environment variable is used —
# since validate_env() already blocks a missing JWT_SECRET via fail-fast in non-production
# environments, no silent default ("dev-secret") is kept here.
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

**`JWT_SECRET` is already validated via fail-fast.** `validate_env()` from [config.md](config.md) instantiates `JwtConfig()`, and if `APP_ENV != "production"` while `secret` is empty, it terminates immediately via `sys.exit(1)`. In production, `main.py`'s `lifespan` looks it up from Secrets Manager (`app/jwt`) and fills it in via `set_jwt_secret()` (see [secret-manager.md](secret-manager.md)), so the `JWT_SECRET` environment variable can be absent — which is why `validate_env()` skips this validation in production.

---

## Credential — password verification

`AuthService` (JWT issuance/verification) only deals with tokens, assuming "an already-authenticated user." Deciding "does this user_id and password actually match" is a separate concern, so it's split out into the `Credential` Aggregate + `CredentialRepository` + the `PasswordHasher` Technical Service.

```python
# src/auth/domain/credential.py — the Credential Aggregate
class Credential:
    def __init__(self, credential_id: str, user_id: str, password_hash: str, created_at: datetime) -> None:
        self.credential_id = credential_id
        self.user_id = user_id
        self.password_hash = password_hash  # a plaintext password is never stored anywhere in domain/application
        self.created_at = created_at

    @classmethod
    def create(cls, user_id: str, password_hash: str) -> Credential:
        return cls(credential_id=generate_id(), user_id=user_id, password_hash=password_hash,
                    created_at=datetime.utcnow())
```

```python
# src/auth/domain/repository.py — the Repository ABC
# The lookup is unified into a single find_credentials(...), the same as the account/card
# domains (repository-pattern.md) — both the user_id uniqueness check (sign-up) and the
# single-item lookup (sign-in) are expressed with a take=1 + user_id filter.
class CredentialRepository(ABC):
    @abstractmethod
    async def find_credentials(
        self, page: int, take: int, credential_id: str | None = None, user_id: str | None = None
    ) -> tuple[list[Credential], int]: ...

    @abstractmethod
    async def save_credential(self, credential: Credential) -> None: ...
```

```python
# src/auth/application/service/password_hasher.py — the Technical Service ABC
class PasswordHasher(ABC):
    @abstractmethod
    async def hash(self, plain_password: str) -> str: ...

    @abstractmethod
    async def verify(self, plain_password: str, password_hash: str) -> bool: ...
```

```python
# src/auth/infrastructure/security/bcrypt_password_hasher.py — the implementation
class BcryptPasswordHasher(PasswordHasher):
    # bcrypt.hashpw/checkpw are synchronous and CPU-bound (hundreds of ms at 12 salt
    # rounds), so they are run in a worker thread via asyncio.to_thread so they don't
    # block the event loop.
    async def hash(self, plain_password: str) -> str:
        return await asyncio.to_thread(self._hash_sync, plain_password)

    async def verify(self, plain_password: str, password_hash: str) -> bool:
        return await asyncio.to_thread(self._verify_sync, plain_password, password_hash)
```

Password hashing follows the same Technical Service pattern as sending email (`NotificationService`) — the ABC lives in `application/service/` and the implementation in `infrastructure/<concern>/`, so Domain/Application never depend on a concrete library such as `bcrypt`. `AuthService` (token issuance/verification) has synchronous methods, but `PasswordHasher` was declared async since it must delegate a CPU-bound task to a thread.

### SignUpHandler / SignInHandler

Plain Handlers that live in `application/command/` (the same as other domains, the router constructs and calls them directly, with no separate Command Bus).

```python
# src/auth/application/command/sign_up_handler.py
class SignUpHandler:
    def __init__(self, repo: CredentialRepository, password_hasher: PasswordHasher) -> None:
        self._repo = repo
        self._password_hasher = password_hasher

    async def execute(self, cmd: SignUpCommand) -> None:
        existing, _ = await self._repo.find_credentials(page=0, take=1, user_id=cmd.user_id)
        if existing:
            raise UserIdAlreadyExistsError()

        password_hash = await self._password_hasher.hash(cmd.password)
        credential = Credential.create(user_id=cmd.user_id, password_hash=password_hash)
        await self._repo.save_credential(credential)
```

```python
# src/auth/application/command/sign_in_handler.py
class SignInHandler:
    def __init__(self, repo: CredentialRepository, password_hasher: PasswordHasher, auth_service: AuthService) -> None:
        self._repo = repo
        self._password_hasher = password_hasher
        self._auth_service = auth_service

    async def execute(self, cmd: SignInCommand) -> str:
        credentials, _ = await self._repo.find_credentials(page=0, take=1, user_id=cmd.user_id)
        credential = credentials[0] if credentials else None
        # Responds with the same error whether the user_id doesn't exist or the password doesn't match — prevents user enumeration
        if credential is None:
            raise InvalidCredentialsError()

        is_valid = await self._password_hasher.verify(cmd.password, credential.password_hash)
        if not is_valid:
            raise InvalidCredentialsError()

        return self._auth_service.issue_token(credential.user_id)
```

`InvalidCredentialsError` is mapped to 401 in `main.py`'s `@app.exception_handler`, and `UserIdAlreadyExistsError` to 400 (both carry `AuthErrorCode` in their `code` field, following the 4-field error-response shape — see [error-handling.md](error-handling.md)).

### Directory structure

```
src/auth/
  domain/
    credential.py              ← the Credential Aggregate
    repository.py              ← CredentialRepository(ABC)
    token.py                   ← the JWT payload model
    errors.py                  ← InvalidTokenError, InvalidCredentialsError, UserIdAlreadyExistsError
    error_codes.py             ← AuthErrorCode
  application/
    command/
      sign_up_handler.py       ← checks for a duplicate user_id → hashes → saves
      sign_in_handler.py       ← looks up the hash → verifies → issues the token
    service/
      auth_service.py          ← AuthService(ABC) — token issuance/verification
      password_hasher.py       ← PasswordHasher(ABC) — password hashing/verification (Technical Service)
  infrastructure/
    jwt_auth_service.py        ← the AuthService implementation (python-jose)
    security/
      bcrypt_password_hasher.py ← the PasswordHasher implementation (bcrypt)
    persistence/
      credential_repository.py ← the CredentialRepository implementation (SQLAlchemy)
  interface/
    rest/
      auth_router.py           ← POST /auth/sign-up, POST /auth/sign-in
      dependencies.py          ← get_current_user (the JWT-verification Depends)
      schemas.py                ← SignUpRequest, SignInRequest, SignInResponse
```

---

### The FastAPI `Depends`-based authentication dependency

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

`fastapi.security.HTTPBearer` handles parsing the `Authorization: Bearer <token>` header and the "header itself is missing" case (401) on our behalf. `get_current_user` only verifies the signature/expiration on top of that.

### Applied to the router

```python
# src/account/interface/rest/account_router.py — actual code
from ....auth.interface.rest.dependencies import CurrentUser, get_current_user

@router.post("/{account_id}/deposit", status_code=201, response_model=TransactionResponse)
async def deposit(
    account_id: str,
    body: DepositRequest,
    current_user: CurrentUser = Depends(get_current_user),   # only a verified user reaches here
    repo: SqlAlchemyAccountRepository = Depends(_repo),
) -> TransactionResponse:
    transaction = await DepositHandler(repo).execute(
        DepositCommand(account_id=account_id, requester_id=current_user.user_id, amount=body.amount)
    )
    ...
```

Hanging it at the router level (a unit roughly equivalent to a class) via `APIRouter(dependencies=[Depends(get_current_user)])` avoids having to redeclare it on every individual endpoint.

```python
router = APIRouter(prefix="/accounts", tags=["Account"], dependencies=[Depends(get_current_user)])
```

**Why it's hung at the router (class-equivalent unit) level:** applying it per endpoint risks forgetting to apply authentication when a new endpoint is added. Only endpoints that don't need authentication, such as `POST /auth/sign-in`, `GET /health/*`, are split into a separate router with no `dependencies` attached.

---

## Token payload design

The JWT payload carries only **the minimal information**.

```python
# correct approach — includes only user_id
{"user_id": "owner-1", "exp": 1234571490}

# incorrect approach — includes sensitive info or frequently-changing info
{"user_id": "...", "email": "...", "role": "...", "permissions": [...]}
```

**Reasons:**
- A JWT payload is only signed, not encrypted (anyone can read it via base64 decoding).
- A role/permission can change after issuance. Putting it in the token means the change isn't reflected immediately.
- If additional user info is needed, it's looked up from the DB at request-processing time.

---

## Bypassing authentication in tests

The Account/Card/Notification E2E tests (`tests/test_account_e2e.py`, etc.) issue a token directly with `JwtAuthService().issue_token(user_id)` and carry it in the `Authorization` header — a valid token signed with an arbitrary `user_id` can be created without going through `POST /auth/sign-up`/`POST /auth/sign-in` (password verification exists only at the sign-in endpoint, and JWT signing/verification itself only needs a `user_id`). Since these domains' concern is "account/card behavior as an authenticated user," not "the login procedure itself," preparing a test user without making real sign-up/sign-in calls is faster and simpler.

```python
def auth_headers(user_id: str) -> dict:
    token = JwtAuthService().issue_token(user_id)
    return {"Authorization": f"Bearer {token}"}
```

The behavior of `POST /auth/sign-up`/`POST /auth/sign-in` itself (successful sign-up, password mismatch, a nonexistent username, a duplicate username, a password-verification failure) is verified by an actual HTTP request in a separate `tests/test_auth_e2e.py` — only this test actually calls sign-up/sign-in.

→ See [testing.md](testing.md) for the detailed testing strategy.

---

### Related documents

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — where authentication sits in the request pipeline
- [layer-architecture.md](layer-architecture.md) — the Interface layer's role
- [config.md](config.md) — environment-variable management, `JWT_SECRET` fail-fast validation
