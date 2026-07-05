# FastAPI DDD 구현 가이드

이 문서는 [backend-service-playbook](../../../docs/architecture/)의 설계 원칙을 FastAPI(Python)로 구현할 때의 언어별 상세를 담는다.

---

## 디렉토리 구조

```
src/
  order/
    domain/
      order.py            ← Aggregate Root
      order_item.py       ← Value Object (dataclass)
      order_cancelled.py  ← Domain Event (dataclass)
      repository.py       ← OrderRepository ABC
      errors.py           ← 도메인 예외
    application/
      command/
        create_order_handler.py
        cancel_order_handler.py
      query/
        get_order_handler.py
        get_orders_handler.py
    infrastructure/
      persistence/
        order_repository.py  ← OrderRepository 구현체 (SQLAlchemy)
    interface/
      rest/
        order_router.py    ← APIRouter
        schemas.py         ← Pydantic 요청·응답 스키마
main.py                    ← FastAPI 앱 생성 + 라우터 등록 + DI 조립
```

---

## 파일명·모듈 네이밍

| 대상 | 규칙 | 예시 |
|------|------|------|
| 파일명·모듈명 | `snake_case.py` | `order_repository.py` |
| 패키지(디렉토리)명 | 소문자 | `domain`, `persistence` |
| 클래스명 | `PascalCase` | `Order`, `OrderRepository` |
| 함수·변수 | `snake_case` | `create_order`, `total_amount` |
| 상수 | `UPPER_SNAKE_CASE` | `MAX_ITEMS` |
| 예외 클래스 | `PascalCase + Error/Exception` | `OrderNotFoundError` |

---

## Repository 패턴

TypeScript의 `abstract class` 대신 Python `ABC`를 사용한다.

```python
# domain/repository.py
from abc import ABC, abstractmethod
from typing import Optional
from .order import Order

class OrderRepository(ABC):

    @abstractmethod
    async def find_by_id(self, order_id: str) -> Optional[Order]: ...

    @abstractmethod
    async def find_all(self, page: int, take: int, status: list[str] | None = None) -> tuple[list[Order], int]: ...

    @abstractmethod
    async def save(self, order: Order) -> None: ...

    @abstractmethod
    async def delete(self, order_id: str) -> None: ...
```

구현체는 `infrastructure/persistence/`에 위치한다.

```python
# infrastructure/persistence/order_repository.py
from sqlalchemy.ext.asyncio import AsyncSession
from ...domain.repository import OrderRepository

class SqlAlchemyOrderRepository(OrderRepository):

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def find_by_id(self, order_id: str) -> Order | None:
        row = await self._session.get(OrderModel, order_id)
        return self._to_domain(row) if row else None
```

---

## CQRS 패턴

Handler 클래스 + `execute` 메서드 방식으로 구현한다.

```python
# application/command/create_order_handler.py
from dataclasses import dataclass
from ...domain.order import Order, ItemInput
from ...domain.repository import OrderRepository

@dataclass
class CreateOrderCommand:
    user_id: str
    items: list[ItemInput]

class CreateOrderHandler:

    def __init__(self, repo: OrderRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: CreateOrderCommand) -> None:
        order = Order.create(cmd.user_id, cmd.items)
        await self._repo.save(order)
```

```python
# application/query/get_order_handler.py
from dataclasses import dataclass
from ...domain.repository import OrderRepository
from ...domain.errors import OrderNotFoundError

@dataclass
class GetOrderQuery:
    order_id: str

@dataclass
class GetOrderResult:
    order_id: str
    status: str
    total_amount: int

class GetOrderHandler:

    def __init__(self, repo: OrderRepository) -> None:
        self._repo = repo

    async def execute(self, query: GetOrderQuery) -> GetOrderResult:
        order = await self._repo.find_by_id(query.order_id)
        if order is None:
            raise OrderNotFoundError(query.order_id)
        return GetOrderResult(
            order_id=order.order_id,
            status=order.status,
            total_amount=order.total_amount(),
        )
```

---

## 에러 처리

도메인 예외를 `domain/errors.py`에 정의하고, 라우터에서 `@app.exception_handler`로 HTTP 상태 코드를 매핑한다.

```python
# domain/errors.py
class OrderNotFoundError(Exception):
    def __init__(self, order_id: str) -> None:
        super().__init__(f"order not found: {order_id}")
        self.order_id = order_id

class OrderAlreadyCancelledError(Exception): ...
class OrderPaidNotCancellableError(Exception): ...
```

```python
# main.py
from fastapi import Request
from fastapi.responses import JSONResponse
from src.order.domain.errors import OrderNotFoundError

@app.exception_handler(OrderNotFoundError)
async def order_not_found_handler(request: Request, exc: OrderNotFoundError):
    return JSONResponse(status_code=404, content={"message": str(exc)})
```

---

## 의존성 주입

FastAPI의 `Depends`를 사용해 Handler를 라우터에 주입한다.

```python
# interface/rest/order_router.py
from fastapi import APIRouter, Depends
from ...application.command.create_order_handler import CreateOrderHandler, CreateOrderCommand

router = APIRouter(prefix="/orders", tags=["Order"])

def get_create_handler(session=Depends(get_session)) -> CreateOrderHandler:
    repo = SqlAlchemyOrderRepository(session)
    return CreateOrderHandler(repo)

@router.post("/", status_code=201)
async def create_order(
    body: CreateOrderRequest,
    handler: CreateOrderHandler = Depends(get_create_handler),
) -> None:
    await handler.execute(CreateOrderCommand(user_id=body.user_id, items=body.items))
```

---

## Soft Delete

`deleted_at: datetime | None` 컬럼으로 관리하며, 쿼리에 `WHERE deleted_at IS NULL` 조건을 항상 포함한다.

```python
class OrderModel(Base):
    __tablename__ = "orders"
    order_id: Mapped[str] = mapped_column(primary_key=True)
    deleted_at: Mapped[datetime | None] = mapped_column(nullable=True, default=None)
```

삭제 시 `deleted_at = datetime.utcnow()`로 설정한다. hard delete 금지.
