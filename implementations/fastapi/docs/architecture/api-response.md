# API 응답 구조

> 프레임워크 무관 원칙: [../../../../docs/architecture/api-response.md](../../../../docs/architecture/api-response.md)

FastAPI에서는 Pydantic `BaseModel`을 응답 스키마로 사용한다. `response_model=`을 라우터 데코레이터에 지정하면 FastAPI가 반환값을 스키마로 직렬화하고 OpenAPI 문서에도 자동 반영한다.

---

## 페이지네이션

오프셋 기반 페이지네이션을 기본으로 사용한다.

| 파라미터 | 타입 | 설명 | 기본값 |
|---------|------|------|--------|
| `page` | `int` | 페이지 번호 (0부터 시작) | 0 |
| `take` | `int` | 페이지 크기 | 20 |

```
GET /accounts/{account_id}/transactions?page=0&take=20
```

`src/account/interface/rest/account_router.py`의 `get_transactions`가 실제 구현이다.

```python
# 실제 코드
@router.get("/{account_id}/transactions", response_model=GetTransactionsResponse)
async def get_transactions(
    account_id: str,
    current_user: CurrentUser = Depends(get_current_user),
    page: int = 0,
    take: int = 20,
    repo: SqlAlchemyAccountRepository = Depends(_repo),
) -> GetTransactionsResponse:
    ...
```

**page는 0부터 시작하는 이유:** `offset = page * take` 계산이 자연스럽다. `page=0`이면 `offset=0`으로 첫 페이지를 가져온다. `SqlAlchemyAccountRepository.find_transactions`도 `stmt.offset(page * take).limit(take)`로 동일하게 구현한다.

---

## 목록 조회 응답 형식

`src/account/interface/rest/schemas.py`의 `GetTransactionsResponse`가 루트 규칙을 정확히 따른다.

```python
class TransactionSummaryResponse(BaseModel):
    transaction_id: str
    type: str
    amount: MoneySchema
    created_at: datetime


class GetTransactionsResponse(BaseModel):
    transactions: list[TransactionSummaryResponse]   # 도메인 객체명 복수형
    count: int                                        # 필터 적용 후 전체 건수
```

```json
{
  "transactions": [
    { "transaction_id": "abc123", "type": "DEPOSIT", "amount": {"amount": 10000, "currency": "KRW"}, "created_at": "..." }
  ],
  "count": 42
}
```

**원칙:**
- 키 이름은 **도메인 객체명 복수형** (`transactions`, `accounts`) — `result`, `data`, `items` 같은 범용 키는 사용하지 않는다.
- `count`는 필터 적용 후 전체 건수이며, 현재 페이지에 반환된 배열 길이(`len(transactions)`)와 같지 않을 수 있다.

---

## 단건 조회 응답 형식

`GetAccountResponse`가 예시다.

```python
class GetAccountResponse(BaseModel):
    account_id: str
    owner_id: str
    email: str
    balance: MoneySchema
    status: str
    created_at: datetime
    updated_at: datetime
```

범용 래퍼(`{"success": true, "data": {...}}`)로 감싸지 않는다. Pydantic 응답 모델을 FastAPI가 그대로 최상위 JSON으로 직렬화한다.

**범용 래퍼를 사용하지 않는 이유:** 에러와 정상 응답의 구분은 HTTP 상태 코드가 담당한다. 래퍼는 중복이며 클라이언트에 불필요한 unwrapping 계층을 추가한다.

---

## Result 객체 — Application 레이어와 Interface 레이어의 분리

`GetAccountHandler`(`src/account/application/query/get_account_handler.py`)는 도메인 `Account` 객체를 그대로 반환하지 않고, `application/query/result.py`의 `GetAccountResult` dataclass로 변환하여 반환한다.

```python
# src/account/application/query/result.py
@dataclass
class MoneyResult:
    amount: int
    currency: str


@dataclass
class GetAccountResult:
    account_id: str
    owner_id: str
    email: str
    balance: MoneyResult
    status: str
    created_at: datetime
    updated_at: datetime
```

라우터(`account_router.py`)가 이 `GetAccountResult`를 Pydantic 응답 모델(`GetAccountResponse`)로 다시 감싼다.

```python
result = await GetAccountHandler(repo).execute(GetAccountQuery(account_id=account_id, requester_id=current_user.user_id))
return GetAccountResponse(
    account_id=result.account_id,
    owner_id=result.owner_id,
    email=result.email,
    balance={"amount": result.balance.amount, "currency": result.balance.currency},
    status=result.status,
    created_at=result.created_at,
    updated_at=result.updated_at,
)
```

**도메인 Aggregate를 응답으로 직접 노출하지 않는 이유:**
- `Account` 도메인 객체는 비즈니스 로직(`deposit`, `withdraw`, ...)과 내부 이벤트 버퍼(`_events`)를 포함한다. 직렬화하면 내부 구현이 외부에 노출된다.
- `Result` dataclass는 조회에 필요한 필드만 포함해 Aggregate보다 가볍고 변경에 유연하다.

세 계층의 역할 분리:

```
Account (domain)  →  GetAccountResult (application/query, dataclass)  →  GetAccountResponse (interface/rest, Pydantic BaseModel)
```

---

## 알려진 격차 — 단건 조회가 별도 메서드로 분리되어 있다

루트 원칙은 "목록 조회 메서드(`find<Noun>s`) 하나만 두고, 단건 조회는 `take: 1` + `pop()` 패턴으로 처리한다"이다. 현재 `AccountRepository`(`src/account/domain/repository.py`)는 `find_by_id`와 `find_all`을 별도 메서드로 분리해 두고 있다 — 이 조회 방식 통일 문제는 [repository-pattern.md](repository-pattern.md)에서 상세히 다룬다. 여기서는 이 분리가 API 응답 계층에 미치는 영향만 짚는다: 단건 조회 엔드포인트(`GET /accounts/{account_id}`)와 목록 조회 엔드포인트(`GET /accounts/{account_id}/transactions`)가 서로 다른 Repository 메서드에 의존하게 되어, 향후 동적 필터가 늘어날 때 두 경로에 중복 구현이 생기기 쉽다.

---

## 동적 필터 조건 패턴

목록 조회 쿼리에서 조건은 값이 있을 때만 적용한다. `SqlAlchemyAccountRepository.find_all`이 이 패턴을 따른다.

```python
async def find_all(
    self, page: int, take: int,
    account_id: str | None = None, owner_id: str | None = None, status: list[str] | None = None,
) -> tuple[list[Account], int]:
    stmt = select(AccountModel).where(AccountModel.deleted_at.is_(None))

    if account_id:
        stmt = stmt.where(AccountModel.id == account_id)
    if owner_id:
        stmt = stmt.where(AccountModel.owner_id == owner_id)
    if status:
        stmt = stmt.where(AccountModel.status.in_(status))
    ...
```

`None`/빈 값을 조건 없이 그대로 넣으면 의도치 않게 전체 조회가 되거나 쿼리 오류가 발생한다. 각 조건을 `if` 가드로 감싼다.

---

### 관련 문서

- [repository-pattern.md](repository-pattern.md) — Repository 메서드 설계, `find<Noun>s` 통일 원칙
- [layer-architecture.md](layer-architecture.md) — Query Handler, Result 객체 배치
