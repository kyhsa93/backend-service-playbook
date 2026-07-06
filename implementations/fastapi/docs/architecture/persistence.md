# 영속성 패턴 — 트랜잭션, Entity 공통 컬럼, Soft Delete, 마이그레이션

> 프레임워크 무관 원칙: [../../../../docs/architecture/persistence.md](../../../../docs/architecture/persistence.md)

## 트랜잭션 — `AsyncSession` + `get_session()`

이 저장소는 SQLAlchemy의 `AsyncSession` 하나를 요청 전체의 Unit of Work로 사용한다. 별도의 `TransactionManager`/`contextvars` 전파 계층 없이, FastAPI의 `Depends`가 세션 생명주기를 요청 단위로 관리한다.

```python
# src/database.py
async def get_session() -> AsyncGenerator[AsyncSession, None]:
    async with SessionLocal() as session:
        yield session
        await session.commit()   # 요청이 성공적으로 끝나면 커밋
```

`interface/rest/account_router.py`의 모든 라우트가 같은 `Depends(get_session)`을 통해 세션을 받고, 그 세션으로 Repository(`_repo`)와 Technical Service(`_notification_service`)를 모두 조립한다 — 즉 하나의 HTTP 요청 안에서 Repository 저장과 알림 발송 기록(`SentEmailModel`)이 **같은 세션, 같은 트랜잭션**에 속한다.

```python
# interface/rest/account_router.py
def _repo(session: AsyncSession = Depends(get_session)) -> SqlAlchemyAccountRepository:
    return SqlAlchemyAccountRepository(session)


def _notification_service(session: AsyncSession = Depends(get_session)) -> NotificationService:
    return SesNotificationService(session)   # 같은 세션 재사용
```

FastAPI가 `Depends(get_session)`을 같은 요청 내에서 캐싱하므로, 두 팩토리는 실제로 동일한 `AsyncSession` 인스턴스를 받는다. 여러 Repository/Service에 걸친 쓰기를 하나의 트랜잭션으로 묶을 때 root가 요구하는 "Unit of Work" 개념이 이 캐싱 동작으로 충족된다 — 언어별로는 `AsyncLocalStorage`/`contextvars`를 쓰는 대신, FastAPI의 요청 스코프 의존성 캐싱이 같은 역할을 한다.

라우트 함수가 성공적으로 반환되면 `get_session()`의 `yield` 다음 줄(`await session.commit()`)이 실행된다. 예외가 발생하면 `async with SessionLocal()`이 세션을 롤백 없이 그냥 닫는다 — 명시적 롤백이 필요하면 `get_session()`에 `except`를 추가한다.

```python
# src/database.py — 명시적 롤백을 원하면
async def get_session() -> AsyncGenerator[AsyncSession, None]:
    async with SessionLocal() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
```

---

## Entity 공통 컬럼 — `created_at`, `updated_at`, `deleted_at`

`AccountModel`(`infrastructure/persistence/account_repository.py`)은 이미 세 컬럼을 모두 갖고 있다.

```python
class AccountModel(Base):
    __tablename__ = "accounts"
    ...
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(default=datetime.utcnow, onupdate=datetime.utcnow)
    deleted_at: Mapped[datetime | None] = mapped_column(nullable=True, default=None)
```

`TransactionModel`은 `created_at`만 갖고 있다 — 거래 내역은 생성 후 변경/삭제되지 않는 불변 기록이므로 `updated_at`/`deleted_at`이 굳이 필요하지 않다. 공통 컬럼 원칙은 "변경 가능한 상태를 갖는 Entity"에 적용되는 것이지, 모든 테이블에 기계적으로 붙이는 규칙이 아니다.

두 번째, 세 번째 도메인이 늘어나면 공통 Mixin으로 추출한다.

```python
# src/common/timestamped_mixin.py (신설 제안)
from datetime import datetime

from sqlalchemy.orm import Mapped, mapped_column


class TimestampedMixin:
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(default=datetime.utcnow, onupdate=datetime.utcnow)
    deleted_at: Mapped[datetime | None] = mapped_column(nullable=True, default=None)
```

---

## Soft Delete — 이미 올바르게 구현됨

`find_by_id`/`find_all`(`infrastructure/persistence/account_repository.py`)은 모두 `AccountModel.deleted_at.is_(None)` 조건을 조회 쿼리에 명시적으로 포함한다.

```python
async def find_by_id(self, account_id: str, owner_id: str) -> Account | None:
    stmt = select(AccountModel).where(
        AccountModel.id == account_id,
        AccountModel.owner_id == owner_id,
        AccountModel.deleted_at.is_(None),
    )
    ...
```

다만 현재 `AccountRepository`(ABC)에는 `delete_account`에 해당하는 메서드 자체가 없다 — 계좌는 `close()`(상태를 `CLOSED`로 전환)만 지원하고 물리적/논리적 삭제 유스케이스가 없기 때문이다. 삭제가 필요한 도메인을 추가하게 되면, hard delete(`session.delete(row)`)가 아니라 `deleted_at = datetime.utcnow()`를 설정하는 soft delete 메서드를 Repository에 추가한다.

```python
# 올바른 방식 — soft delete (신설 시 참고)
async def delete_account(self, account_id: str) -> None:
    row = await self._session.get(AccountModel, account_id)
    if row is not None:
        row.deleted_at = datetime.utcnow()
```

---

## 알려진 격차 — 스키마 관리에 Alembic이 없다

`main.py`의 `lifespan`이 기동할 때마다 `Base.metadata.create_all`을 호출한다.

```python
# main.py — 현재
@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncGenerator[None, None]:
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield
```

`create_all`은 **테이블이 없을 때만 생성**하며, 기존 테이블의 컬럼 추가/변경/삭제는 감지하지 못한다. 로컬 개발과 `tests/`의 testcontainers 환경(매번 새 컨테이너)에서는 문제없이 동작하지만, 프로덕션에서 스키마를 변경해야 할 때(컬럼 추가, 인덱스 추가 등) 이 방식으로는 반영할 방법이 없다 — root 문서가 명시적으로 경고하는 "자동 스키마 동기화를 운영에 쓰지 않는다"는 원칙을 위반한다. 이 문서 작성 시점 기준 이 격차는 코드에 남아 있다.

### 올바른 패턴 — Alembic 마이그레이션

```bash
pip install alembic
alembic init migrations
```

```python
# migrations/env.py — target_metadata를 프로젝트의 Base로 연결
from src.account.infrastructure.persistence.account_repository import Base
target_metadata = Base.metadata
```

```bash
# 스키마 변경 후 마이그레이션 생성 (모델 변경을 자동 감지)
alembic revision --autogenerate -m "add sent_emails table"

# 마이그레이션 적용
alembic upgrade head

# 마지막 마이그레이션 롤백
alembic downgrade -1
```

```python
# main.py — lifespan에서 create_all 제거, 마이그레이션은 배포 파이프라인에서 실행
@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncGenerator[None, None]:
    # Base.metadata.create_all 호출 제거 — 스키마는 `alembic upgrade head`로 배포 시점에 적용
    yield
```

`create_all`은 로컬 개발·테스트 환경(매번 새 DB, 스키마 검증이 목적이 아님)에서는 계속 사용해도 무방하다 — `tests/test_account_e2e.py`가 testcontainers Postgres에 대해 매번 `create_all`을 호출하는 것은 적절하다. 문제는 **운영 환경 기동 경로에서도 동일한 코드(`main.py`의 `lifespan`)를 공유한다는 점**이다. 마이그레이션 도입 시 `create_all` 호출을 프로덕션 기동 경로에서 제거하고, 테스트 fixture에서만 유지한다.

```
migrations/versions/
  20260101_000000_create_accounts.py
  20260201_000000_add_sent_emails.py
```

---

## 원칙

- **하나의 요청 = 하나의 `AsyncSession`**: `Depends(get_session)`의 요청 스코프 캐싱을 트랜잭션 경계로 사용한다.
- **모든 상태 변경 가능한 Entity에 `created_at`/`updated_at`/`deleted_at`을 둔다**: 불변 기록(`TransactionModel`)은 예외.
- **삭제는 soft delete**: `deleted_at` 타임스탬프. 조회는 항상 `deleted_at IS NULL`.
- **스키마 변경은 Alembic으로 관리한다**: `create_all`은 로컬/테스트 전용으로 한정한다.

---

### 관련 문서

- [repository-pattern.md](repository-pattern.md) — Repository 인터페이스/구현 분리, 메서드 네이밍
- [domain-events.md](domain-events.md) — Outbox 저장도 같은 세션/트랜잭션에서 처리
- [testing.md](testing.md) — testcontainers에서의 `create_all` 사용
