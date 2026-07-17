# Aggregate ID 생성

> 프레임워크 무관 원칙: [../../../../docs/architecture/aggregate-id.md](../../../../docs/architecture/aggregate-id.md)

### 원칙

- **ID 생성 위치**: Domain 레이어 (Aggregate의 팩토리 classmethod, `Account.create()` 등)
- **생성 주체**: 서버. 클라이언트가 제공한 ID를 신뢰하지 않는다.
- **타입**: `str`
- **형식**: UUID v4에서 하이픈을 제거한 32자리 hex 문자열

```python
"550e8400e29b41d4a716446655440000"    # 올바른 방식 — 32자리, 하이픈 없음
"550e8400-e29b-41d4-a716-446655440000"  # 잘못된 방식 — 하이픈 포함
1, 2, 3                                  # 잘못된 방식 — auto-increment 숫자
```

**auto-increment 숫자 ID를 사용하지 않는 이유:**
- DB 레코드 수·생성 순서가 외부에 노출된다 (보안)
- 여러 서비스·샤드 간 ID 충돌 가능성이 있다
- ID가 DB insert 시점까지 결정되지 않아 Domain 레이어에서 미리 생성할 수 없다

---

### `generate_id()`

`src/account/domain/account.py`의 `Account.create()`, `src/account/domain/transaction.py`의 `Transaction.create()`, `notification_service.py`가 발급하는 sent_email ID 전부 아래 `generate_id()`(하이픈 제거 32자리 hex, `uuid.uuid4().hex`)를 사용한다. `outbox_writer.py`는 `uuid.uuid4().hex`를 직접 사용해 동일한 형식을 유지한다.

---

### ID 생성 유틸

프로젝트 공통 유틸로 뽑아 모든 도메인이 재사용한다.

```python
# src/common/generate_id.py
import uuid


def generate_id() -> str:
    return uuid.uuid4().hex
```

---

### Aggregate에서 사용

```python
# src/account/domain/account.py
from __future__ import annotations
from datetime import datetime

from src.common.generate_id import generate_id
from .money import Money
from .account_status import AccountStatus


class Account:
    @classmethod
    def create(cls, owner_id: str, currency: str, email: str) -> Account:
        now = datetime.utcnow()
        account = cls(
            account_id=generate_id(),          # 올바른 방식 — 하이픈 없는 32자리 hex
            owner_id=owner_id,
            email=email,
            balance=Money(0, currency),
            status=AccountStatus.ACTIVE,
            created_at=now,
            updated_at=now,
        )
        ...
        return account
```

같은 방식을 `Transaction.create()`(`src/account/domain/transaction.py`)의 `transaction_id`에도 적용한다.

- **신규 생성**: 팩토리 classmethod(`Account.create`, `Transaction.create`) 내부에서 `generate_id()`로 자동 할당한다.
- **DB 복원**: `SqlAlchemyAccountRepository._to_domain()`(`src/account/infrastructure/persistence/account_repository.py`)이 저장된 `account_id`를 그대로 `Account.__init__`에 전달한다 — 새 ID를 만들지 않는다.

---

### Repository 구현체에서 ID 처리

Repository는 Aggregate가 이미 가진 ID를 그대로 사용한다. DB에서 새 ID를 발급하지 않는다.

```python
# src/account/infrastructure/persistence/account_repository.py
async def save(self, account: Account) -> None:
    existing = await self._session.get(AccountModel, account.account_id)
    if existing:
        existing.amount = account.balance.amount
        existing.status = account.status.value
    else:
        self._session.add(AccountModel(
            id=account.account_id,   # Aggregate가 이미 가진 ID를 그대로 사용
            owner_id=account.owner_id,
            ...
        ))
```

---

### 컬럼 타입 — DB에서도 고정 길이로 정의

32자리 고정 길이 문자열이므로 가변 길이 `VARCHAR`보다 `CHAR(32)`로 선언하는 것이 정확하다. 현재 `AccountModel.id`는 `Mapped[str]`(기본 `VARCHAR`)로 선언돼 있으며, 하이픈 제거 규칙을 적용한 뒤에는 다음과 같이 고정 길이를 명시할 수 있다.

```python
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy import CHAR

id: Mapped[str] = mapped_column(CHAR(32), primary_key=True)
```

---

### 하위 Entity/객체 ID

Aggregate 내부의 `Transaction`(하위 Entity 성격)도 동일하게 `generate_id()` 기반 32자리 hex 문자열을 사용한다. Value Object(`Money`)는 식별자를 가지지 않으므로 ID 규칙과 무관하다.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate/Entity/Value Object 구분
- [repository-pattern.md](repository-pattern.md) — Repository에서 Aggregate 저장
