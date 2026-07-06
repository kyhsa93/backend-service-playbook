# 코딩 컨벤션

## 1. 파일 네이밍 규칙

- 모든 파일명: `snake_case.py`
- 패키지(디렉토리)명: 소문자 (`domain`, `application`, `persistence`, `notification`)
- Command/Query Handler: `<verb>_<noun>_handler.py` (`application/command/` 또는 `application/query/`에 배치) — 같은 파일에 `<Verb><Noun>Command`/`<Verb><Noun>Query` dataclass와 `<Verb><Noun>Handler` 클래스를 함께 둔다
- Query Result: `result.py` (`application/query/`에 배치, 여러 Result dataclass를 한 파일에 모아도 무방)
- Adapter 인터페이스: `<external_domain>_adapter.py` (`application/adapter/`에 배치)
- Adapter 구현체: `<provider>_<external_domain>_adapter.py` 또는 `in_process_<external_domain>_adapter.py` (`infrastructure/`에 배치) — 구현 기술을 접두사로 붙인다
- Technical Service 인터페이스: `<concern>_service.py` (`application/service/`에 배치) — `notification_service.py`, `storage_service.py`, `secret_service.py` 등
- Technical Service 구현체: `<provider>_<concern>_service.py` (`infrastructure/<concern>/`에 배치) — `ses_notification_service.py`, `s3_storage_service.py`
- Repository 인터페이스: `repository.py` 또는 `<aggregate>_repository.py` (`domain/`에 배치)
- Repository 구현체: `<aggregate>_repository.py` (`infrastructure/persistence/`에 배치, 구현 기술은 클래스명에 `SqlAlchemy` 접두사로 표시하고 파일명 자체는 그대로 둔다)
- Aggregate Root: `<aggregate_root>.py` (domain 레이어)
- Entity(하위 개체): `<entity>.py` (domain 레이어)
- Value Object: `<value_object>.py` (domain 레이어)
- Domain Event: `events.py` (domain 레이어, 도메인당 하나의 파일에 이벤트를 모은다)
- 도메인 예외: `errors.py` (domain 레이어)
- 에러 코드: `error_codes.py` (domain 레이어, 메시지와 1:1 매핑)
- 도메인 enum: `<concern>_status.py` 등 의미 단위로 분리 (예: `account_status.py`, `order_status.py`) — 모듈 루트가 아니라 `domain/`에 위치 (Python은 파일 자체가 이미 네임스페이스이므로 NestJS처럼 모듈 루트에 별도로 모으지 않는다)
- 상수: `constants.py` (도메인 패키지 루트, 필요할 때만 생성)
- Router: `<domain>_router.py` (`interface/rest/`에 배치)
- Interface DTO(Pydantic): `schemas.py` (`interface/rest/`에 배치, 요청/응답 모델을 한 파일에 모은다)
- ID 생성 유틸: `generate_id.py` (`src/common/`에 배치, 여러 도메인이 공유)
- 설정 클래스: `<concern>_config.py` (`src/config/`에 배치) — `database_config.py`, `jwt_config.py`
- 설정 검증: `validator.py` (`src/config/`에 배치)

---

## 2. 클래스 네이밍 규칙

- Aggregate Root: `Order`, `Account` (도메인 명사, `PascalCase`)
- Entity(하위 개체): `OrderItem`, `Transaction`
- Value Object: `Money`, `Address`
- Domain Event: `OrderPlaced`, `OrderCancelled` (과거형)
- Repository 인터페이스: `OrderRepository`, `AccountRepository` (구현 기술 이름을 넣지 않는다)
- Repository 구현체: `SqlAlchemy<Aggregate>Repository` — `SqlAlchemyOrderRepository`
- Command: `<Verb><Noun>Command` — `CancelOrderCommand`, `CreateOrderCommand`
- CommandHandler: `<Verb><Noun>Handler` — `CancelOrderHandler`, `CreateOrderHandler`
- Query: `<Verb><Noun>Query` — `GetOrderQuery`, `GetOrdersQuery`
- QueryHandler: `<Verb><Noun>Handler` — `GetOrderHandler`, `GetOrdersHandler`
- Result: `<Verb><Noun>Result` / `<Noun>Result` — `GetOrderResult`, `GetOrdersResult`, `OrderSummaryResult`
- Adapter 인터페이스: `<ExternalDomain>Adapter` — `PaymentAdapter`
- Adapter 구현체: `<Provider><ExternalDomain>Adapter` — `InProcessPaymentAdapter`, `HttpPaymentAdapter`
- Technical Service 인터페이스: `<Concern>Service` — `NotificationService`, `StorageService`, `SecretService` (구현 기술 이름을 넣지 않는다)
- Technical Service 구현체: `<Provider><Concern>Service` — `SesNotificationService`, `S3StorageService`, `AwsSecretService`
- 도메인 예외: `PascalCase` + `Error` — `OrderNotFoundError`, `OrderAlreadyCancelledError`
- 에러 코드 enum: `<Domain>ErrorCode` (`str, Enum` 상속, 값은 `SCREAMING_SNAKE_CASE` 고정 문자열) — `OrderErrorCode`
- Pydantic 요청/응답 모델: `<Verb><Noun>Request` / `<Verb><Noun>Response` — `CreateOrderRequest`, `GetOrderResponse`
- 함수·변수: `snake_case` — `create_order`, `pull_events`
- 상수: `UPPER_SNAKE_CASE` — `MAX_ORDER_AMOUNT`

---

## 3. Enum / 상수 배치 규칙

- Python은 파일 자체가 네임스페이스이므로, NestJS처럼 "모듈 루트에 `<domain>-enum.ts` 하나로 모은다"는 규칙이 그대로 적용되지 않는다 — 대신 **의미 단위로 분리해 `domain/`에 둔다**.
- 도메인 상태값처럼 Aggregate의 일부인 enum은 `domain/<concern>_status.py`에 정의한다.
- 여러 애그리거트가 공유하는 상수는 도메인 패키지 루트의 `constants.py`에 모은다. 도메인 하나에 국한된 상수는 사용하는 파일 옆에 두어도 무방하다 — 인라인 매직 넘버/문자열만 피하면 된다.

```python
# src/order/domain/order_status.py
from enum import Enum


class OrderStatus(str, Enum):
    PENDING = "pending"
    PAID = "paid"
    CANCELLED = "cancelled"
```

```python
# src/order/constants.py
MAX_ORDER_AMOUNT = 9_999_999
ALLOWED_ORDER_STATUSES = [OrderStatus.PENDING, OrderStatus.PAID]
```

- Application 레이어(Command/Query/Result)에서 사용하는 enum도 `domain/`에서 import해서 재사용한다 — Application에 별도로 enum을 다시 정의하지 않는다.

---

## 4. 타이핑 패턴

### Aggregate Root — 일반 클래스, dataclass 자동 생성자 사용 금지

상태가 변하고 불변식을 스스로 지켜야 하는 Aggregate Root는 `__init__` + 도메인 메서드를 가진 일반 클래스로 작성한다. `@dataclass`의 자동 생성 `__init__`은 필드를 자유롭게 덮어쓸 수 있어 불변식 보호에 적합하지 않다.

```python
# 올바른 방식 — 일반 클래스
class Order:
    def __init__(self, order_id: str, user_id: str, items: list[OrderItem], status: OrderStatus) -> None:
        if not items:
            raise OrderMustHaveAtLeastOneItemError()
        self.order_id = order_id
        self.user_id = user_id
        self.items = items
        self.status = status
        self._events: list[OrderDomainEvent] = []
```

```python
# 잘못된 방식 — Aggregate Root를 dataclass로 선언
@dataclass
class Order:
    order_id: str
    items: list[OrderItem]
    status: OrderStatus   # 외부에서 order.status = "cancelled" 처럼 임의로 덮어쓸 수 있다
```

### Entity / Value Object / Domain Event — `@dataclass(frozen=True)`

생성 이후 값이 바뀌지 않는 하위 개체·값 객체·이벤트는 `frozen=True` dataclass로 선언한다. 필드 재할당 시 `FrozenInstanceError`가 발생하므로 불변성이 구조적으로 보장된다.

```python
@dataclass(frozen=True)
class OrderItem:
    item_id: str
    name: str
    price: int
    quantity: int

    def __post_init__(self) -> None:
        if self.price <= 0:
            raise InvalidPriceError()
        if self.quantity <= 0:
            raise InvalidQuantityError()
```

동등성 비교가 필요한 Value Object는 `frozen=True` dataclass가 자동 생성하는 `__eq__`(속성 기반 동등성)를 그대로 사용한다 — 별도 `equals()` 메서드를 만들지 않는다.

### Command / Query — `@dataclass`, 필드는 타입 힌트로 명시

```python
@dataclass
class CancelOrderCommand:
    order_id: str
    reason: str
    refund_amount: int | None = None
```

- Command/Query는 (Handler 생성자 주입과 달리) 매 요청마다 새로 만들어지므로 `frozen=True`를 강제하지 않아도 되지만, 실수로 필드를 변경하지 않도록 `frozen=True`를 붙이는 것을 권장한다.
- 필드에 기본값이 필요하면 dataclass 필드 기본값을 사용한다 — NestJS의 `Object.assign(this, command)` 생성자 패턴에 대응하는 것은 dataclass의 자동 생성 `__init__`이다.

### 리터럴/enum 타입 — 도메인 상태값에 사용

```python
# 올바른 방식 — enum 또는 Literal
status: OrderStatus
result: Literal["success", "fail"]

# 잘못된 방식 — 임의 문자열 허용
status: str
```

### 시간대 규칙 — UTC 저장, 타임존 인식 datetime

- 이 저장소는 `datetime.utcnow()`(타임존 정보 없는 naive UTC)를 사용해 DB에 저장하고, 조회 시에도 변환 없이 그대로 반환한다 — NestJS 구현이 KST(UTC+9)로 변환해 저장하는 것과 달리, FastAPI/SQLAlchemy 구현은 **UTC로 통일**하고 클라이언트가 필요 시 타임존을 변환한다.
- 새 프로젝트에서는 `datetime.now(timezone.utc)`(타임존 인식 aware datetime)를 쓰는 것을 권장한다 — `datetime.utcnow()`는 Python 3.12부터 deprecated다.

```python
# 권장 — 타임존 인식 UTC
from datetime import datetime, timezone

created_at = datetime.now(timezone.utc)
```

```python
# 지양 — 타임존 정보 없는 naive datetime (Python 3.12+ deprecated)
created_at = datetime.utcnow()
```

- 저장·조회 모두 UTC를 유지한다 — 저장 시점과 조회 시점에 서로 다른 변환을 적용하면 이중 변환 오류가 발생한다는 원칙은 언어와 무관하게 동일하다.

### Null 처리 규칙

- DB에서 오는 nullable 필드: `T | None`
- optional 파라미터(기본값이 있는 키워드 인자): `T | None = None` 또는 타입 자체에 기본값
- Python에는 `any` 타입에 해당하는 것이 `Any`(`typing.Any`)이며, 사용을 금지한다 — 구체적인 타입 또는 `Union`/제네릭으로 명시한다.

### ORM — Repository 구현체에서 `AsyncSession` 주입

```python
class SqlAlchemyOrderRepository(OrderRepository):
    def __init__(self, session: AsyncSession) -> None:
        self._session = session
```

전용 DI 컨테이너가 없으므로 `AsyncSession`은 생성자 인자로 직접 받는다 — `Depends` 팩토리가 이 생성자 호출을 담당한다([module-pattern.md](architecture/module-pattern.md) 참조).

### 복잡한 타입 — type alias 또는 `Union`

```python
from typing import Union

OrderDomainEvent = Union[OrderPlaced, OrderCancelled]
```

Python 3.10+에서는 `|` 문법도 가능하다: `OrderDomainEvent = OrderPlaced | OrderCancelled`.

---

## 5. REST API 엔드포인트 설계 규칙

### URL 구조 — 리소스 중심, 복수 명사

URL은 **행위(동사)가 아닌 리소스(명사)**를 나타낸다. HTTP 메서드가 행위를 표현한다.

```
# 올바른 방식
GET    /orders              주문 목록 조회
GET    /orders/{order_id}   주문 단건 조회
POST   /orders              주문 생성
PUT    /orders/{order_id}   주문 전체 수정
PATCH  /orders/{order_id}   주문 부분 수정
DELETE /orders/{order_id}   주문 삭제

# 잘못된 방식
GET    /getOrders            동사를 URL에 넣지 않는다
POST   /createOrder          동사를 URL에 넣지 않는다
GET    /order/{order_id}     단수형 사용 금지 — 항상 복수형
```

`APIRouter(prefix="/orders", tags=["Order"])`로 라우터 자체에 리소스 prefix를 고정하면, 각 라우트 함수는 `@router.get("/{order_id}")`처럼 상대 경로만 작성한다.

### HTTP 메서드와 응답 코드

| 메서드 | 용도 | 성공 코드 | 응답 바디 |
|--------|------|----------|----------|
| `GET` | 리소스 조회 | 200 OK | 있음 |
| `POST` | 리소스 생성 | 201 Created | 선택 |
| `PUT` | 리소스 전체 수정 | 200 OK | 있음 |
| `PATCH` | 리소스 부분 수정 | 200 OK | 있음 |
| `DELETE` | 리소스 삭제 | 204 No Content | 없음 |

`@router.post("", status_code=201, ...)`처럼 `status_code`를 라우트 데코레이터에 명시한다 — FastAPI 기본값(200)을 그대로 두지 않는다.

### 비 CRUD 행위 — 하위 리소스 경로

```
POST   /orders/{order_id}/cancel        주문 취소
POST   /orders/{order_id}/refund        주문 환불
POST   /accounts/{account_id}/suspend   계좌 정지
```

### 계층 관계 — 중첩 리소스

2단계까지만 중첩하고, 그 이상은 최상위 리소스로 분리한다.

```
# 올바른 방식 — 2단계 중첩
GET  /orders/{order_id}/items
GET  /orders/{order_id}/items/{item_id}

# 잘못된 방식 — 3단계 이상 중첩
GET  /users/{user_id}/orders/{order_id}/items/{item_id}
# → 대신 최상위로 분리
GET  /order-items/{item_id}
```

### 목록 조회 — 페이지네이션과 필터링

```
GET /orders?page=0&take=20&status=pending&status=paid
```

- 페이지네이션: `page`(0부터 시작), `take`(페이지 크기) 쿼리 파라미터로 라우트 함수 인자에 직접 선언한다 (`page: int = 0, take: int = 20`).
- 필터: querystring 파라미터로 전달, 값이 있을 때만 조건을 적용한다.

### URL 네이밍 규칙

- **복수 명사**: `/orders`, `/users` (단수형 사용 금지)
- **kebab-case**: `/order-items`, `/payment-methods` (Python 코드 자체는 `snake_case`지만 URL 경로는 kebab-case를 유지한다 — 언어 컨벤션과 URL 컨벤션은 별개다)
- **소문자만 사용**
- **후행 슬래시 없음**: `/orders/`(X) → `/orders`(O)
- **파일 확장자 없음**: `/orders.json`(X) → `/orders`(O)

### Deprecated 엔드포인트

FastAPI는 라우트 데코레이터에 `deprecated=True`를 지정하면 OpenAPI 스키마와 `/docs`(Swagger UI)에 자동으로 취소선과 "Deprecated" 배지가 표시된다.

```python
@router.post("", status_code=201, deprecated=True)
async def create_order(...) -> CreateOrderResponse: ...
```

- 즉시 삭제하지 않고 `deprecated=True`로 표시해 클라이언트가 마이그레이션할 시간을 확보한다.
- 호출이 발생하면 `logger.warning(...)`으로 기록해 잔존 사용자를 추적한다.

---

## 6. 메서드 네이밍 및 구성

### 라우트 함수

- `get`, `find`, `create`, `update`, `delete`, `suspend`, `cancel`, `transfer` 등 동사 사용
- 모두 `async def`이며 반환 타입 명시: `-> ResponseModel`
- 로직 없이 Handler 생성 + `execute()` 호출, 응답 변환만 수행

### Handler(Command/QueryHandler) 메서드 구성 순서

1. `__init__` — 의존성(Repository/Adapter/Technical Service ABC) 주입, `self._xxx` 형태로 private 관례(단일 언더스코어 접두사) 사용
2. `async def execute(self, cmd_or_query) -> ResultType` — 공개 조율 메서드
3. private 헬퍼 메서드(필요 시) — 단일 언더스코어 접두사

```python
class CancelOrderHandler:
    def __init__(self, repo: OrderRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: CancelOrderCommand) -> None:
        ...
```

### Handler 메서드 반환 타입 — 항상 명시

```python
# 올바른 방식
async def execute(self, query: GetOrderQuery) -> GetOrderResult: ...
async def execute(self, cmd: CancelOrderCommand) -> None: ...

# 잘못된 방식 — 반환 타입 누락
async def execute(self, query: GetOrderQuery): ...
```

### private 환경 분기 헬퍼

```python
def _payment_api_url() -> str:
    if os.getenv("APP_ENV") == "production":
        return "https://payment.api.example.com"
    return "https://payment.dev.api.example.com"
```

---

## 7. import 구성 패턴

### 절대 임포트를 기본으로, 같은 레이어 내부는 상대 임포트 허용

이 저장소의 실제 코드(`examples/src/account/`)는 도메인 패키지 내부에서는 상대 임포트(`from ...domain.account import Account`)를, 다른 최상위 패키지를 참조할 때는 절대 임포트(`from src.database import get_session`)를 섞어 쓴다. 아래 기준을 따른다.

- **같은 도메인 패키지 내부**(`domain/` → `domain/`, `application/` → `domain/` 등): 상대 임포트를 사용해도 된다 — 패키지를 통째로 다른 이름으로 이동해도 내부 참조가 깨지지 않는다.
- **다른 최상위 패키지 참조**(`src.database`, `src.common`, `src.auth`, 다른 도메인의 `application/adapter/`): 절대 임포트(`from src.xxx import ...`)를 사용한다 — 상대 임포트로 여러 단계(`from ....database import ...`)를 거슬러 올라가면 가독성이 떨어진다.

```python
# src/account/interface/rest/account_router.py — 실제 코드 패턴
from ...application.command.deposit_handler import DepositCommand, DepositHandler   # 같은 도메인 내부 — 상대
from ....database import get_session                                                  # 다른 최상위 패키지 — 상대(4단계)도 허용되지만
from src.database import get_session                                                  # 절대 임포트가 더 읽기 쉽다면 이 형태도 허용
```

### 2그룹 순서 — 표준 라이브러리/서드파티 → 내부 모듈

```python
# 1. 표준 라이브러리 + 서드파티 패키지
from dataclasses import dataclass
from datetime import datetime

from fastapi import APIRouter, Depends, Header
from sqlalchemy.ext.asyncio import AsyncSession

# 2. 내부 모듈 (상대 또는 절대)
from ...domain.errors import OrderNotFoundError
from ...domain.order_repository import OrderRepository
```

`isort`/`ruff --select I`로 그룹 순서와 알파벳 정렬을 자동화하는 것을 권장한다.

### 순환 import — top-level import를 지연 import로 해소

Python은 NestJS의 `forwardRef()`에 대응하는 문법이 없다. 대신 `TYPE_CHECKING`(타입 힌트만 필요할 때) 또는 함수 내부 import(런타임에 실제 값이 필요할 때)로 해소한다. 상세: [module-pattern.md](architecture/module-pattern.md).

### `__init__.py` — 빈 파일로 두거나 패키지 마커로만 사용

`__init__.py`에서 하위 모듈을 re-export하지 않는다 — import 경로가 이원화되면(패키지 경로로도, 하위 모듈 경로로도 접근 가능) 어느 쪽이 정식 경로인지 혼란스러워진다.

---

## 8. Pydantic 응답 모델 / OpenAPI 문서화 패턴

FastAPI는 NestJS의 `@ApiProperty`/`DocumentBuilder`처럼 수동으로 조립하는 절차 없이, Pydantic 모델과 라우트 시그니처만으로 OpenAPI 스키마와 Swagger UI(`/docs`)를 자동 생성한다. 이 저장소에서 사람이 직접 작성해야 하는 것은 **Pydantic 모델 필드와 라우트 데코레이터 인자**뿐이다.

### `response_model` — 라우트 데코레이터에 항상 명시

```python
@router.get("/{order_id}", response_model=GetOrderResponse)
async def get_order(order_id: str, ...) -> GetOrderResponse: ...
```

`response_model`과 함수 반환 타입 힌트를 동일하게 맞춘다 — 둘 다 명시하면 FastAPI가 실제로 필드를 걸러내는 직렬화(불필요한 내부 필드 노출 방지)와 정적 타입 체커(mypy)의 이점을 모두 얻는다.

### 요청 모델 — `Field(...)`로 설명·제약 추가

```python
from pydantic import BaseModel, Field


class CreateOrderRequest(BaseModel):
    description: str = Field(..., min_length=1, max_length=200, description="주문 설명")
    delivery_type: Literal["standard", "express"] = Field(..., description="배송 유형")
```

- `Field(..., min_length=..., max_length=...)`가 NestJS `@IsString()` + `@MinLength()` + `@MaxLength()` 조합에 대응한다 — Pydantic은 검증과 문서화를 같은 선언으로 처리한다.
- `Literal["standard", "express"]`가 NestJS `@IsEnum()` + `@ApiProperty({ enum: [...] })`에 대응한다.

### Optional 필드 — `| None = None` + `Field(None, description=...)`

```python
class FindOrdersRequestQuery(BaseModel):
    keyword: str | None = Field(None, description="검색 키워드")
    page: int = Field(0, ge=0, description="페이지 번호 (0부터 시작)")
    take: int = Field(20, ge=1, le=100, description="페이지 크기")
```

FastAPI에서 querystring 파라미터는 별도 DTO 클래스 없이 라우트 함수 인자로 직접 선언해도 된다(`page: int = 0, take: int = Query(20, ge=1, le=100)`) — 필드 수가 많아지면 위처럼 Pydantic 모델로 묶어 `Depends()`로 주입받는다.

### 배열/nullable 필드 — 타입 힌트가 곧 문서

```python
class GetOrdersResult:
    orders: list[OrderSummaryResult]   # NestJS의 @ApiProperty({ type: [ItemClass] })에 대응
    total_count: int
```

```python
class GetOrderResponse(BaseModel):
    description: str | None   # NestJS의 @ApiProperty({ nullable: true, type: String })에 대응
```

별도 데코레이터 없이 타입 힌트 자체가 OpenAPI `nullable`/`items` 스키마로 변환된다.

### Result(Application) vs Response(Interface) — 필드 선언 위치

Application 레이어의 Result는 `dataclass`이므로 Pydantic `Field`/검증 데코레이터를 붙이지 않는다 — 검증·문서화는 Interface 레이어의 Pydantic 모델(`schemas.py`)에만 작성한다. NestJS가 "Query/Result 클래스에 `@ApiProperty`를 작성한다"는 것과 반대로, FastAPI 구현은 문서화 책임을 Interface 레이어에 둔다.

### deprecated / 요약 / 설명

```python
@router.get(
    "/{order_id}",
    response_model=GetOrderResponse,
    summary="주문 단건 조회",
    description="주문 ID로 단건 주문 정보를 조회한다.",
)
async def get_order(order_id: str, ...) -> GetOrderResponse: ...
```

---

## 9. 로거 패턴

### 항상 모듈 최상위에서 `getLogger(__name__)`

```python
import logging

logger = logging.getLogger(__name__)
```

NestJS의 `new Logger(XxxController.name)`(클래스 필드)와 달리, Python 표준 `logging`은 모듈 단위로 로거를 얻는 것이 관용적이다 — 클래스 안에 별도 필드로 두지 않는다.

### 구조화된 JSON 로그 — `extra=`로 필드 전달

```python
# 에러 로그
logger.exception("알림 이메일 발송 실패: account_id=%s", account_id)

# 정보 로그 — extra=로 구조화된 필드 전달 (snake_case 필드명)
logger.info("주문 완료", extra={"order_id": order_id, "amount": amount})
```

`%s` 문자열 보간만으로는 사람이 읽기엔 충분하지만, 구조화 로깅(JSON 포매터)을 적용하면 `extra=`에 담은 필드가 최상위 JSON 키로 병합되어 CloudWatch/Datadog에서 바로 필터링할 수 있다 — 상세: [observability.md](architecture/observability.md).

---

## 10. 주석 스타일

- 비즈니스 도메인 설명은 팀의 기본 언어(한글)로 인라인 주석(`#`) 작성
- Docstring(`"""..."""`)은 공개 API(Handler의 `execute()`, Repository ABC의 추상 메서드)에 한 줄 요약 정도로만 사용 — NestJS의 "JSDoc 금지"만큼 엄격하지는 않지만, 장황한 여러 줄 docstring보다 짧은 인라인 주석을 우선한다.
- 긴 Handler 메서드는 섹션 주석으로 논리적 구분을 표시한다.

```python
async def execute(self, cmd: CancelOrderCommand) -> None:
    # 주문 조회
    orders, _ = await self._repo.find_orders(page=0, take=1, order_id=cmd.order_id)
    order = orders[0] if orders else None
    if order is None:
        raise OrderNotFoundError(cmd.order_id)

    # 비즈니스 규칙 검증 및 상태 변경은 Aggregate에 위임
    order.cancel(cmd.reason)

    # 저장 — Outbox 적재까지 포함
    await self._repo.save(order)
```

---

## 11. 커밋 메시지 컨벤션

루트 [conventions.md](../../../docs/conventions.md)의 [Conventional Commits](https://www.conventionalcommits.org/) 규칙을 그대로 따른다 — 언어별로 다른 규칙을 두지 않는다.

### 메시지 구조

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

### type 목록

| type | 설명 | 예시 |
|------|------|------|
| `feat` | 새로운 기능 추가 | `feat(order): 주문 취소 기능 추가` |
| `fix` | 버그 수정 | `fix(order): 결제 완료 후 주문 상태가 업데이트되지 않는 현상 수정` |
| `refactor` | 기능 변경 없이 코드 구조 변경 | `refactor(order): 조회 로직 Repository로 이동` |
| `docs` | 문서만 변경 | `docs: README 프로젝트 구조 설명 업데이트` |
| `test` | 테스트 추가 또는 수정 | `test(order): 주문 취소 불변식 단위 테스트 추가` |
| `chore` | 빌드, CI, 의존성 등 코드 외적인 작업 | `chore(deps): fastapi 버전 업그레이드` |
| `style` | 코드 포맷팅 등 동작에 영향 없는 변경 | `style: import 정렬 수정 (ruff)` |
| `perf` | 성능 개선 | `perf(order): 주문 목록 조회 쿼리 최적화` |

### scope / description 규칙

- scope는 **서비스 도메인명**을 사용한다: `order`, `account`, `payment`, `auth` 등
- 여러 도메인에 걸친 변경이면 scope를 생략하거나 상위 개념을 사용한다
- description은 한글, 서술형("추가", "수정", "제거"), 첫 글자 대문자 시작 없음, 끝에 마침표 없음

### BREAKING CHANGE

```
feat(order)!: 주문 응답 스키마 변경

BREAKING CHANGE: GetOrderResponse의 total_price → total_amount 필드명 변경
```

### 예시

```
feat(order): 주문 취소 기능 추가

fix(order): 결제 완료 후 주문 상태가 업데이트되지 않는 현상 수정 (#123)

refactor(account): find_by_id/find_all을 find_accounts로 통합

repository-pattern.md의 root 컨벤션(find<Noun>s 단일 메서드)에 맞춰
조회 경로를 하나로 합치고 호출부를 take=1 패턴으로 수정.

docs: enum 배치 규칙 추가

test(order): 주문 생성 불변식 단위 테스트 추가
```

---

## 12. 브랜치 및 PR 컨벤션

루트 [conventions.md](../../../docs/conventions.md)의 Conventional Branch 규칙을 그대로 따른다.

### 브랜치 네이밍

```
<type>/<scope>-<short-description>
```

| type | 용도 | 예시 |
|------|------|------|
| `feat` | 새 기능 개발 | `feat/order-cancel` |
| `fix` | 버그 수정 | `fix/order-status-update` |
| `refactor` | 리팩터링 | `refactor/account-repository-find-unify` |
| `docs` | 문서 변경 | `docs/architecture-adapter-pattern` |
| `test` | 테스트 추가/수정 | `test/order-cancel-invariant` |
| `chore` | 빌드, CI, 의존성 | `chore/deps-fastapi-upgrade` |

**규칙:**
- 모든 단어는 `kebab-case`로 작성한다 (Python 코드의 `snake_case`와는 별개).
- scope가 불필요하면 생략한다.
- `main` 브랜치에서 분기하고, `main`에 직접 commit/push하지 않는다.

### PR 워크플로우

```
1. main에서 새 브랜치 생성
   git checkout main && git pull origin main
   git checkout -b <type>/<scope>-<short-description>

2. 작업 후 commit (Conventional Commits 형식)
   git add <files>
   git commit -m "<type>(<scope>): <description>"

3. 원격에 push
   git push -u origin <branch-name>

4. main 브랜치로 PR 생성
   gh pr create --base main --title "<type>(<scope>): <description>" --body "..."
```

### PR 제목 / 본문

PR 제목은 Conventional Commits 형식과 동일하게 작성한다.

```markdown
## Summary
- 변경 사항을 1~3줄로 요약

## Test plan
- [ ] 테스트 항목 1
- [ ] 테스트 항목 2
```

### 머지 전략

- **Squash and merge**를 기본으로 사용한다.
- 머지 후 원격 브랜치는 자동 삭제한다.

---

## 13. 테스트 패턴

상세 원칙(3계층 구조, mock 전략, testcontainers)은 [architecture/testing.md](architecture/testing.md)를 참조한다. 이 절은 네이밍·배치 규칙만 요약한다.

### 단위 테스트 — Domain 레이어

프레임워크·mock 없이 Aggregate를 직접 인스턴스화해서 검증한다.

```python
# tests/unit/domain/test_order.py
import pytest

from src.order.domain.errors import OrderAlreadyCancelledError, OrderMustHaveAtLeastOneItemError
from src.order.domain.order import Order


def test_create_주문_항목이_비어있으면_에러를_던진다() -> None:
    with pytest.raises(OrderMustHaveAtLeastOneItemError):
        Order.create(user_id="user-1", items=[])


def test_cancel_이미_취소된_주문을_다시_취소하면_에러를_던진다() -> None:
    order = Order.create(user_id="user-1", items=[make_item()])
    order.cancel("변심")

    with pytest.raises(OrderAlreadyCancelledError):
        order.cancel("변심")
```

### 단위 테스트 — Application Handler

Repository/Adapter/Technical Service ABC를 `unittest.mock.AsyncMock`으로 대체한다.

```python
# tests/unit/application/test_cancel_order_handler.py
from unittest.mock import AsyncMock

import pytest

from src.order.application.command.cancel_order_handler import CancelOrderCommand, CancelOrderHandler
from src.order.domain.errors import OrderNotFoundError


@pytest.mark.asyncio
async def test_execute_주문이_존재하지_않으면_에러를_던진다() -> None:
    repo = AsyncMock()
    repo.find_orders.return_value = ([], 0)
    handler = CancelOrderHandler(repo)

    with pytest.raises(OrderNotFoundError):
        await handler.execute(CancelOrderCommand(order_id="non-existent", reason="변심"))
```

### E2E 테스트 — `httpx.ASGITransport` + testcontainers

```python
# tests/test_order_e2e.py
from httpx import ASGITransport, AsyncClient
from testcontainers.postgres import PostgresContainer


@pytest_asyncio.fixture(scope="session")
async def client():
    with PostgresContainer("postgres:16-alpine") as postgres:
        ...
        app.dependency_overrides[get_session] = override_get_session
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as ac:
            yield ac


@pytest.mark.asyncio
async def test_주문_취소_성공(client) -> None:
    response = await client.post(f"/orders/{order_id}/cancel", json={"reason": "변심"})
    assert response.status_code == 204
```

`pytest.ini`의 `asyncio_mode = auto`로 모든 `async def test_*`를 자동으로 `pytest-asyncio` 대상에 포함시킨다 — 개별 테스트에 `@pytest.mark.asyncio`를 매번 붙이지 않아도 된다(이 저장소에서는 명시적으로 붙이는 스타일과 혼용된다. 팀에서 하나로 통일한다).

### 테스트 파일 배치

```
tests/
  unit/
    domain/
      test_order.py
    application/
      test_cancel_order_handler.py
  test_order_e2e.py
```

### 테스트 네이밍 패턴

```
test_<메서드>_<조건>_<기대_결과>
예: test_cancel_이미_취소된_주문을_다시_취소하면_에러를_던진다
예(영문 스타일도 허용): test_cancel_order_when_already_cancelled_then_raises
```

기존 E2E 테스트가 영문 스타일(`test_deposit_success`)을 쓰고 있다면, 새 테스트도 그 파일 안에서는 일관되게 영문 스타일을 유지한다 — 파일 단위로는 하나의 스타일을 지킨다.
