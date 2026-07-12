# 실전 구현 템플릿

전체 도메인 하나(Order)를 본 아키텍처로 구현한 예시다. 새 도메인을 추가할 때 이 템플릿을 복사하여 시작한다.

> 이 문서는 **목표 상태(target state)** 를 보여준다. 저장소의 실제 예시(`examples/src/account/`)는 [architecture/*.md](architecture/) 각 문서의 "알려진 격차" 절에 정리된 것처럼 이 목표의 일부(Alembic 마이그레이션, Repository 조회 메서드 네이밍 등)를 아직 반영하지 않았다 — 새 도메인을 작성할 때는 `examples/`를 그대로 베끼지 말고, 이 문서와 각 architecture 문서의 "올바른 패턴" 절을 기준으로 삼는다. 에러 코드/Outbox/인증은 이미 `examples/`에 반영되어 있다.

---

## 디렉토리 구조

```
src/
  config/
    database_config.py             ← DB 설정 (pydantic-settings)
    jwt_config.py                  ← JWT 설정 (pydantic-settings)
    validator.py                   ← 기동 시 fail-fast 검증
  common/
    generate_id.py                 ← uuid.uuid4().hex 기반 ID 생성 유틸
    error_response.py               ← statusCode/code/message/error 표준 응답 빌더
    correlation.py                  ← contextvars 기반 Correlation ID
  auth/
    application/
      service/
        auth_service.py             ← AuthService(ABC) — 토큰 발급/검증 인터페이스
    infrastructure/
      jwt_auth_service.py           ← JwtAuthService(AuthService) — python-jose 구현체
    interface/
      rest/
        dependencies.py             ← get_current_user() — Depends로 라우터에서 공유
  order/
    domain/
      order.py                     ← Aggregate Root
      order_item.py                ← Value Object
      order_status.py              ← 도메인 enum
      events.py                    ← Domain Event 모음
      errors.py                    ← 도메인 예외 계층
      error_codes.py                ← 에러 코드 enum (예외와 1:1)
      repository.py                ← OrderRepository(ABC), PaymentRepository(ABC)
    application/
      adapter/
        payment_adapter.py          ← 외부 도메인 호출 인터페이스 (ABC)
      service/
        crypto_service.py           ← 기술 인프라 인터페이스 (ABC)
      command/
        create_order_handler.py     ← CreateOrderCommand + CreateOrderHandler
        cancel_order_handler.py     ← CancelOrderCommand + CancelOrderHandler
        delete_order_handler.py     ← DeleteOrderCommand + DeleteOrderHandler
      query/
        result.py                   ← GetOrderResult, GetOrdersResult 등
        get_order_handler.py        ← GetOrderQuery + GetOrderHandler
        get_orders_handler.py       ← GetOrdersQuery + GetOrdersHandler
    infrastructure/
      persistence/
        order_repository.py         ← Base, OrderModel, OrderItemModel, SqlAlchemyOrderRepository
        outbox_model.py             ← OutboxModel — Domain Event 적재 테이블
      payment_adapter_impl.py       ← InProcessPaymentAdapter(PaymentAdapter)
      crypto_service_impl.py        ← AesCryptoService(CryptoService)
      scheduling/
        order_cleanup_scheduler.py  ← APScheduler — Cron(선택, 만료 주문 정리 등이 필요할 때)
    interface/
      rest/
        order_router.py             ← APIRouter, Depends 조립, Handler 호출
        schemas.py                  ← Pydantic 요청/응답 모델
  database.py                       ← 엔진/세션 팩토리, get_session() 의존성
main.py                              ← FastAPI 앱 생성, lifespan, 라우터/예외 핸들러 등록
```

---

## Domain 레이어

### Aggregate Root

Aggregate Root는 상태가 변하고 불변식을 스스로 지켜야 하므로 `@dataclass`가 아닌 일반 클래스로 작성한다 — [tactical-ddd.md](architecture/tactical-ddd.md) 참조.

```python
# domain/order.py — 프레임워크 무의존
from __future__ import annotations

from datetime import datetime, timezone
from typing import Union

from src.common.generate_id import generate_id
from .errors import (
    OrderAlreadyCancelledError,
    OrderMustHaveAtLeastOneItemError,
    OrderPaidCannotBeCancelledError,
)
from .events import OrderCancelled, OrderPlaced
from .order_item import OrderItem
from .order_status import OrderStatus

OrderDomainEvent = Union[OrderPlaced, OrderCancelled]


class Order:
    def __init__(
        self,
        order_id: str,
        user_id: str,
        items: list[OrderItem],
        status: OrderStatus,
        created_at: datetime,
    ) -> None:
        if not items:
            raise OrderMustHaveAtLeastOneItemError()
        self.order_id = order_id
        self.user_id = user_id
        self.items = items
        self.status = status
        self.created_at = created_at
        self._events: list[OrderDomainEvent] = []

    @classmethod
    def create(cls, user_id: str, items: list[OrderItem]) -> Order:
        now = datetime.now(timezone.utc)
        order = cls(
            order_id=generate_id(),   # 하이픈 제거 32자리 hex — aggregate-id.md
            user_id=user_id,
            items=items,
            status=OrderStatus.PENDING,
            created_at=now,
        )
        order._events.append(
            OrderPlaced(order_id=order.order_id, user_id=user_id, total_amount=order.total_amount, placed_at=now)
        )
        return order

    @property
    def total_amount(self) -> int:
        return sum(item.price * item.quantity for item in self.items)

    def cancel(self, reason: str) -> None:
        if self.status == OrderStatus.CANCELLED:
            raise OrderAlreadyCancelledError()
        if self.status == OrderStatus.PAID:
            raise OrderPaidCannotBeCancelledError()
        self.status = OrderStatus.CANCELLED
        self._events.append(
            OrderCancelled(order_id=self.order_id, reason=reason, cancelled_at=datetime.now(timezone.utc))
        )

    def pull_events(self) -> list[OrderDomainEvent]:
        events, self._events = self._events, []
        return events
```

### Value Object

```python
# domain/order_item.py — 불변 객체
from dataclasses import dataclass

from .errors import InvalidPriceError, InvalidQuantityError


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

`frozen=True` dataclass가 자동 생성하는 `__eq__`가 속성 기반 동등성 비교를 대신하므로 별도 `equals()` 메서드를 만들지 않는다.

### 도메인 enum

```python
# domain/order_status.py
from enum import Enum


class OrderStatus(str, Enum):
    PENDING = "pending"
    PAID = "paid"
    CANCELLED = "cancelled"
```

### Domain Event

```python
# domain/events.py
from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class OrderPlaced:
    order_id: str
    user_id: str
    total_amount: int
    placed_at: datetime


@dataclass(frozen=True)
class OrderCancelled:
    order_id: str
    reason: str
    cancelled_at: datetime
```

### 도메인 예외 + 에러 코드

```python
# domain/error_codes.py
from enum import Enum


class OrderErrorCode(str, Enum):
    ORDER_NOT_FOUND = "ORDER_NOT_FOUND"
    ORDER_ALREADY_CANCELLED = "ORDER_ALREADY_CANCELLED"
    ORDER_PAID_CANNOT_BE_CANCELLED = "ORDER_PAID_CANNOT_BE_CANCELLED"
    ORDER_MUST_HAVE_AT_LEAST_ONE_ITEM = "ORDER_MUST_HAVE_AT_LEAST_ONE_ITEM"
    INVALID_PRICE = "INVALID_PRICE"
    INVALID_QUANTITY = "INVALID_QUANTITY"
    PAYMENT_METHOD_NOT_FOUND = "PAYMENT_METHOD_NOT_FOUND"
```

```python
# domain/errors.py
from .error_codes import OrderErrorCode


class OrderError(Exception):
    code: OrderErrorCode


class OrderNotFoundError(OrderError):
    code = OrderErrorCode.ORDER_NOT_FOUND

    def __init__(self, order_id: str) -> None:
        super().__init__(f"주문을 찾을 수 없습니다: {order_id}")
        self.order_id = order_id


class OrderAlreadyCancelledError(OrderError):
    code = OrderErrorCode.ORDER_ALREADY_CANCELLED

    def __init__(self) -> None:
        super().__init__("이미 취소된 주문입니다.")


class OrderPaidCannotBeCancelledError(OrderError):
    code = OrderErrorCode.ORDER_PAID_CANNOT_BE_CANCELLED

    def __init__(self) -> None:
        super().__init__("결제 완료된 주문은 취소할 수 없습니다.")


class OrderMustHaveAtLeastOneItemError(OrderError):
    code = OrderErrorCode.ORDER_MUST_HAVE_AT_LEAST_ONE_ITEM

    def __init__(self) -> None:
        super().__init__("주문 항목은 최소 1개 이상이어야 합니다.")


class InvalidPriceError(OrderError):
    code = OrderErrorCode.INVALID_PRICE

    def __init__(self) -> None:
        super().__init__("상품 가격은 0보다 커야 합니다.")


class InvalidQuantityError(OrderError):
    code = OrderErrorCode.INVALID_QUANTITY

    def __init__(self) -> None:
        super().__init__("수량은 0보다 커야 합니다.")


class PaymentMethodNotFoundError(OrderError):
    code = OrderErrorCode.PAYMENT_METHOD_NOT_FOUND

    def __init__(self, order_id: str) -> None:
        super().__init__(f"결제 정보를 찾을 수 없습니다: {order_id}")
```

### Repository 인터페이스

```python
# domain/repository.py — ABC
from abc import ABC, abstractmethod

from .order import Order


class OrderRepository(ABC):
    @abstractmethod
    async def find_orders(
        self,
        page: int,
        take: int,
        order_id: str | None = None,
        user_id: str | None = None,
        status: list[OrderStatus] | None = None,
    ) -> tuple[list[Order], int]: ...

    @abstractmethod
    async def save(self, order: Order) -> None: ...

    @abstractmethod
    async def delete(self, order_id: str) -> None: ...


class PaymentRepository(ABC):
    @abstractmethod
    async def find_payment_methods(self, order_id: str, page: int, take: int) -> tuple[list["PaymentMethod"], int]: ...

    @abstractmethod
    async def delete_payment_methods(self, order_id: str) -> None: ...
```

조회 메서드를 `find_orders` 하나로 통일하고 단건 조회는 `take=1` 뒤 인덱싱으로 꺼낸다 — [repository-pattern.md](architecture/repository-pattern.md)의 "root 컨벤션에 맞춘 형태" 참조.

---

## Application 레이어

### Command Handler

```python
# application/command/create_order_handler.py
from dataclasses import dataclass

from ...domain.order import Order
from ...domain.order_item import OrderItem
from ...domain.repository import OrderRepository


@dataclass(frozen=True)
class CreateOrderCommand:
    user_id: str
    items: list[dict]


class CreateOrderHandler:
    def __init__(self, repo: OrderRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: CreateOrderCommand) -> Order:
        order = Order.create(user_id=cmd.user_id, items=[OrderItem(**item) for item in cmd.items])
        await self._repo.save(order)   # Outbox 적재까지 save() 내부에서 처리
        return order
```

```python
# application/command/cancel_order_handler.py
from dataclasses import dataclass

from ...domain.errors import OrderNotFoundError
from ...domain.repository import OrderRepository, PaymentRepository


@dataclass(frozen=True)
class CancelOrderCommand:
    order_id: str
    reason: str
    refund_amount: int | None = None


class CancelOrderHandler:
    def __init__(self, repo: OrderRepository, payment_repo: PaymentRepository) -> None:
        self._repo = repo
        self._payment_repo = payment_repo

    async def execute(self, cmd: CancelOrderCommand) -> None:
        # 주문 조회 — find_orders(take=1) + 인덱싱 패턴
        orders, _ = await self._repo.find_orders(page=0, take=1, order_id=cmd.order_id)
        order = orders[0] if orders else None
        if order is None:
            raise OrderNotFoundError(cmd.order_id)

        # 비즈니스 규칙은 Aggregate 내부에서 검증
        order.cancel(cmd.reason)

        # 여러 Repository에 걸친 쓰기 — 같은 AsyncSession(같은 Depends(get_session))으로 묶인다
        await self._payment_repo.delete_payment_methods(order.order_id)
        await self._repo.save(order)   # Aggregate 상태 + pull_events()를 Outbox에 함께 저장
```

```python
# application/command/delete_order_handler.py
from dataclasses import dataclass

from ...domain.errors import OrderNotFoundError
from ...domain.repository import OrderRepository


@dataclass(frozen=True)
class DeleteOrderCommand:
    order_id: str


class DeleteOrderHandler:
    def __init__(self, repo: OrderRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: DeleteOrderCommand) -> None:
        orders, _ = await self._repo.find_orders(page=0, take=1, order_id=cmd.order_id)
        if not orders:
            raise OrderNotFoundError(cmd.order_id)
        await self._repo.delete(cmd.order_id)
```

**흐름:** Repository에서 Aggregate 조회 → Aggregate의 도메인 메서드 호출 → Repository로 저장. Handler 자신은 비즈니스 규칙을 갖지 않고 Aggregate에 위임한다 — [layer-architecture.md](architecture/layer-architecture.md) 참조.

### Query Handler + Result

```python
# application/query/result.py
from dataclasses import dataclass
from datetime import datetime


@dataclass
class OrderItemResult:
    item_id: str
    name: str
    price: int
    quantity: int


@dataclass
class GetOrderResult:
    order_id: str
    user_id: str
    items: list[OrderItemResult]
    status: str
    total_amount: int
    created_at: datetime


@dataclass
class OrderSummaryResult:
    order_id: str
    status: str
    total_amount: int


@dataclass
class GetOrdersResult:
    orders: list[OrderSummaryResult]
    count: int
```

```python
# application/query/get_order_handler.py
from dataclasses import dataclass

from ...domain.errors import OrderNotFoundError
from ...domain.repository import OrderRepository
from .result import GetOrderResult, OrderItemResult


@dataclass(frozen=True)
class GetOrderQuery:
    order_id: str


class GetOrderHandler:
    def __init__(self, repo: OrderRepository) -> None:
        self._repo = repo

    async def execute(self, query: GetOrderQuery) -> GetOrderResult:
        orders, _ = await self._repo.find_orders(page=0, take=1, order_id=query.order_id)
        order = orders[0] if orders else None
        if order is None:
            raise OrderNotFoundError(query.order_id)

        return GetOrderResult(
            order_id=order.order_id,
            user_id=order.user_id,
            items=[OrderItemResult(i.item_id, i.name, i.price, i.quantity) for i in order.items],
            status=order.status.value,
            total_amount=order.total_amount,
            created_at=order.created_at,
        )
```

```python
# application/query/get_orders_handler.py
from dataclasses import dataclass

from ...domain.order_status import OrderStatus
from ...domain.repository import OrderRepository
from .result import GetOrdersResult, OrderSummaryResult


@dataclass(frozen=True)
class GetOrdersQuery:
    page: int = 0
    take: int = 20
    status: list[OrderStatus] | None = None


class GetOrdersHandler:
    def __init__(self, repo: OrderRepository) -> None:
        self._repo = repo

    async def execute(self, query: GetOrdersQuery) -> GetOrdersResult:
        orders, count = await self._repo.find_orders(page=query.page, take=query.take, status=query.status)
        return GetOrdersResult(
            orders=[OrderSummaryResult(o.order_id, o.status.value, o.total_amount) for o in orders],
            count=count,
        )
```

QueryHandler는 도메인 Aggregate를 직접 반환하지 않고 Result dataclass로 변환한다 — [api-response.md](architecture/api-response.md) 참조.

### Adapter 인터페이스 (크로스 도메인)

```python
# application/adapter/payment_adapter.py
from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass(frozen=True)
class UserSummary:
    user_id: str
    is_active: bool


class PaymentAdapter(ABC):
    @abstractmethod
    async def find_user(self, user_id: str) -> UserSummary | None: ...
```

호출하는 쪽(Order)이 필요로 하는 형태로만 메서드를 정의한다 — 대상 도메인(User)의 전체 API를 노출하지 않는다. 상세: [cross-domain.md](architecture/cross-domain.md).

### Technical Service 인터페이스

```python
# application/service/crypto_service.py
from abc import ABC, abstractmethod


class CryptoService(ABC):
    @abstractmethod
    def encrypt(self, plain_text: str) -> str: ...

    @abstractmethod
    def decrypt(self, cipher_text: str) -> str: ...
```

---

## Infrastructure 레이어

### Repository 구현체

```python
# infrastructure/persistence/order_repository.py
import json
from datetime import datetime, timezone

from sqlalchemy import CHAR, ForeignKey, func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship

from ...domain.order import Order
from ...domain.order_item import OrderItem
from ...domain.order_status import OrderStatus
from ...domain.repository import OrderRepository
from .outbox_model import OutboxModel


class Base(DeclarativeBase):
    pass


class OrderModel(Base):
    __tablename__ = "orders"

    id: Mapped[str] = mapped_column(CHAR(32), primary_key=True)   # 하이픈 제거 32자리 hex — aggregate-id.md
    user_id: Mapped[str]
    status: Mapped[str]
    created_at: Mapped[datetime] = mapped_column(default=lambda: datetime.now(timezone.utc))
    updated_at: Mapped[datetime] = mapped_column(
        default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc)
    )
    deleted_at: Mapped[datetime | None] = mapped_column(nullable=True, default=None)

    items: Mapped[list["OrderItemModel"]] = relationship(back_populates="order", cascade="all, delete-orphan")


class OrderItemModel(Base):
    __tablename__ = "order_items"

    id: Mapped[str] = mapped_column(CHAR(32), primary_key=True)
    order_id: Mapped[str] = mapped_column(ForeignKey("orders.id"))
    name: Mapped[str]
    price: Mapped[int]
    quantity: Mapped[int]

    order: Mapped[OrderModel] = relationship(back_populates="items")


class SqlAlchemyOrderRepository(OrderRepository):
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def find_orders(
        self,
        page: int,
        take: int,
        order_id: str | None = None,
        user_id: str | None = None,
        status: list[OrderStatus] | None = None,
    ) -> tuple[list[Order], int]:
        stmt = select(OrderModel).where(OrderModel.deleted_at.is_(None))
        count_stmt = select(func.count()).select_from(OrderModel).where(OrderModel.deleted_at.is_(None))

        # 동적 필터 — 값이 있을 때만 적용, count에도 동일하게 적용
        if order_id:
            stmt = stmt.where(OrderModel.id == order_id)
            count_stmt = count_stmt.where(OrderModel.id == order_id)
        if user_id:
            stmt = stmt.where(OrderModel.user_id == user_id)
            count_stmt = count_stmt.where(OrderModel.user_id == user_id)
        if status:
            stmt = stmt.where(OrderModel.status.in_([s.value for s in status]))
            count_stmt = count_stmt.where(OrderModel.status.in_([s.value for s in status]))

        stmt = stmt.order_by(OrderModel.created_at.desc()).offset(page * take).limit(take)

        rows = (await self._session.execute(stmt)).scalars().all()
        count = (await self._session.execute(count_stmt)).scalar_one()

        # DB 모델 → 도메인 Aggregate 변환
        return [self._to_domain(row) for row in rows], count

    async def save(self, order: Order) -> None:
        existing = await self._session.get(OrderModel, order.order_id)
        if existing:
            existing.status = order.status.value
        else:
            self._session.add(
                OrderModel(
                    id=order.order_id,
                    user_id=order.user_id,
                    status=order.status.value,
                    items=[
                        OrderItemModel(id=i.item_id, name=i.name, price=i.price, quantity=i.quantity)
                        for i in order.items
                    ],
                )
            )

        # Domain Event → Outbox — 같은 세션(같은 트랜잭션)에 함께 적재
        for event in order.pull_events():
            self._session.add(
                OutboxModel(
                    event_id=generate_id(),
                    event_type=type(event).__name__,
                    payload=json.dumps(event.__dict__, default=str),
                    processed=False,
                )
            )

        await self._session.flush()

    async def delete(self, order_id: str) -> None:
        # cascade soft delete — 하위 Entity 먼저
        row = await self._session.get(OrderModel, order_id)
        if row is not None:
            now = datetime.now(timezone.utc)
            row.deleted_at = now

    def _to_domain(self, row: OrderModel) -> Order:
        return Order(
            order_id=row.id,
            user_id=row.user_id,
            items=[OrderItem(item_id=i.id, name=i.name, price=i.price, quantity=i.quantity) for i in row.items],
            status=OrderStatus(row.status),
            created_at=row.created_at,
        )
```

### Adapter 구현체

```python
# infrastructure/payment_adapter_impl.py
from ..application.adapter.payment_adapter import PaymentAdapter, UserSummary


class InProcessPaymentAdapter(PaymentAdapter):
    def __init__(self, get_user_handler) -> None:
        self._get_user_handler = get_user_handler

    async def find_user(self, user_id: str) -> UserSummary | None:
        result = await self._get_user_handler.execute(user_id)
        if result is None:
            return None
        return UserSummary(user_id=result.user_id, is_active=result.is_active)
```

같은 프로세스(모놀리스) 안에서는 대상 도메인의 Handler를 직접 호출하는 in-process 구현으로 시작한다. 서비스가 분리되면 같은 ABC를 구현하는 `HttpPaymentAdapter`(내부 HTTP 클라이언트)로 교체한다 — 호출하는 쪽(Order)의 코드는 바뀌지 않는다. 상세: [cross-domain.md](architecture/cross-domain.md).

### Technical Service 구현체

```python
# infrastructure/crypto_service_impl.py
import base64
import os

from cryptography.fernet import Fernet

from ..application.service.crypto_service import CryptoService


class AesCryptoService(CryptoService):
    def __init__(self, key: bytes) -> None:
        self._fernet = Fernet(key)

    def encrypt(self, plain_text: str) -> str:
        return self._fernet.encrypt(plain_text.encode()).decode()

    def decrypt(self, cipher_text: str) -> str:
        return self._fernet.decrypt(cipher_text.encode()).decode()
```

### Scheduler (선택 — Cron이 필요할 때)

```python
# infrastructure/scheduling/order_cleanup_scheduler.py
import logging

from apscheduler.schedulers.asyncio import AsyncIOScheduler

logger = logging.getLogger(__name__)


def start_order_cleanup_scheduler(session_factory) -> AsyncIOScheduler:
    scheduler = AsyncIOScheduler()

    @scheduler.scheduled_job("cron", hour=0, minute=0, id="order-cleanup-expired")
    async def _run() -> None:
        try:
            async with session_factory() as session:
                # 만료 주문 정리 — 비즈니스 로직은 Handler에 위임하거나 여기서 직접 최소한만 수행
                ...
        except Exception:
            logger.exception("order_cleanup_scheduler_failed")   # 예외를 삼키지 않고 명시적으로 로깅

    scheduler.start()
    return scheduler
```

Scheduler는 비즈니스 로직을 직접 실행하지 않고 트리거 역할만 한다 — 상세: [scheduling.md](architecture/scheduling.md).

---

## Interface 레이어

### Pydantic 스키마

```python
# interface/rest/schemas.py
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field


class OrderItemRequest(BaseModel):
    item_id: str
    name: str
    price: int = Field(..., gt=0)
    quantity: int = Field(..., gt=0)


class CreateOrderRequest(BaseModel):
    items: list[OrderItemRequest] = Field(..., min_length=1, description="주문 항목 목록")


class CancelOrderRequest(BaseModel):
    reason: str = Field(..., min_length=1, description="취소 사유")
    refund_amount: int | None = Field(None, ge=0, description="환불 금액")


class OrderItemResponse(BaseModel):
    item_id: str
    name: str
    price: int
    quantity: int


class CreateOrderResponse(BaseModel):
    order_id: str
    user_id: str
    items: list[OrderItemResponse]
    status: str
    total_amount: int
    created_at: datetime


class GetOrderResponse(BaseModel):
    order_id: str
    user_id: str
    items: list[OrderItemResponse]
    status: str
    total_amount: int
    created_at: datetime


class OrderSummaryResponse(BaseModel):
    order_id: str
    status: str
    total_amount: int


class GetOrdersResponse(BaseModel):
    orders: list[OrderSummaryResponse]   # 도메인 객체명 복수형 — result/data 금지
    count: int
```

### Router

```python
# interface/rest/order_router.py
from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from src.auth.interface.rest.dependencies import CurrentUser, get_current_user
from src.database import get_session
from ...application.command.cancel_order_handler import CancelOrderCommand, CancelOrderHandler
from ...application.command.create_order_handler import CreateOrderCommand, CreateOrderHandler
from ...application.command.delete_order_handler import DeleteOrderCommand, DeleteOrderHandler
from ...application.query.get_order_handler import GetOrderHandler, GetOrderQuery
from ...application.query.get_orders_handler import GetOrdersHandler, GetOrdersQuery
from ...domain.order_status import OrderStatus
from ...infrastructure.persistence.order_repository import SqlAlchemyOrderRepository
from .schemas import (
    CancelOrderRequest,
    CreateOrderRequest,
    CreateOrderResponse,
    GetOrderResponse,
    GetOrdersResponse,
)

router = APIRouter(prefix="/orders", tags=["Order"], dependencies=[Depends(get_current_user)])


def _repo(session: AsyncSession = Depends(get_session)) -> SqlAlchemyOrderRepository:
    return SqlAlchemyOrderRepository(session)


@router.post("", status_code=201, response_model=CreateOrderResponse)
async def create_order(
    body: CreateOrderRequest,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemyOrderRepository = Depends(_repo),
) -> CreateOrderResponse:
    order = await CreateOrderHandler(repo).execute(
        CreateOrderCommand(user_id=current_user.user_id, items=[item.model_dump() for item in body.items])
    )
    return CreateOrderResponse(
        order_id=order.order_id,
        user_id=order.user_id,
        items=[
            {"item_id": i.item_id, "name": i.name, "price": i.price, "quantity": i.quantity} for i in order.items
        ],
        status=order.status.value,
        total_amount=order.total_amount,
        created_at=order.created_at,
    )


@router.post("/{order_id}/cancel", status_code=204)
async def cancel_order(
    order_id: str,
    body: CancelOrderRequest,
    repo: SqlAlchemyOrderRepository = Depends(_repo),
) -> None:
    await CancelOrderHandler(repo).execute(
        CancelOrderCommand(order_id=order_id, reason=body.reason, refund_amount=body.refund_amount)
    )


@router.delete("/{order_id}", status_code=204)
async def delete_order(
    order_id: str,
    repo: SqlAlchemyOrderRepository = Depends(_repo),
) -> None:
    await DeleteOrderHandler(repo).execute(DeleteOrderCommand(order_id=order_id))


@router.get("/{order_id}", response_model=GetOrderResponse)
async def get_order(
    order_id: str,
    repo: SqlAlchemyOrderRepository = Depends(_repo),
) -> GetOrderResponse:
    result = await GetOrderHandler(repo).execute(GetOrderQuery(order_id=order_id))
    return GetOrderResponse(
        order_id=result.order_id,
        user_id=result.user_id,
        items=[
            {"item_id": i.item_id, "name": i.name, "price": i.price, "quantity": i.quantity} for i in result.items
        ],
        status=result.status,
        total_amount=result.total_amount,
        created_at=result.created_at,
    )


@router.get("", response_model=GetOrdersResponse)
async def get_orders(
    page: int = 0,
    take: int = 20,
    status: list[OrderStatus] | None = None,
    repo: SqlAlchemyOrderRepository = Depends(_repo),
) -> GetOrdersResponse:
    result = await GetOrdersHandler(repo).execute(GetOrdersQuery(page=page, take=take, status=status))
    return GetOrdersResponse(
        orders=[{"order_id": o.order_id, "status": o.status, "total_amount": o.total_amount} for o in result.orders],
        count=result.count,
    )
```

라우터 레벨의 `dependencies=[Depends(get_current_user)]`가 모든 라우트에 인증을 일괄 적용한다 — 개별 라우트마다 반복 선언하지 않는다. 상세: [authentication.md](architecture/authentication.md).

---

## 배선 (Depends 팩토리 = NestJS의 `{ provide, useClass }`)

FastAPI에는 전용 DI 컨테이너가 없으므로, ABC ↔ 구현체 바인딩은 라우터 파일의 팩토리 함수(`_repo`, `_payment_adapter` 등) 자체가 담당한다 — 상세: [module-pattern.md](architecture/module-pattern.md).

```python
# interface/rest/order_router.py — Adapter/Technical Service까지 배선하는 경우
def _payment_adapter(session: AsyncSession = Depends(get_session)) -> PaymentAdapter:
    return InProcessPaymentAdapter(get_user_handler=...)


def _crypto_service() -> CryptoService:
    return AesCryptoService(key=os.environ["CRYPTO_KEY"].encode())
```

### main.py — 라우터 조합 + 예외 핸들러 등록

```python
# main.py
import logging
from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from src.common.error_response import build_error_response
from src.config.validator import validate_env
from src.order.domain.errors import OrderError, OrderNotFoundError
from src.order.infrastructure.persistence.order_repository import Base
from src.order.interface.rest.order_router import router as order_router
from src.database import engine

logger = logging.getLogger(__name__)
app_state = {"is_shutting_down": False}


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncGenerator[None, None]:
    validate_env()   # fail-fast — 필수 환경 변수 누락 시 여기서 프로세스 종료
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)   # 로컬/테스트 전용 — 운영은 Alembic
    logger.info("app_started")

    yield

    app_state["is_shutting_down"] = True
    logger.info("shutdown_initiated")
    await engine.dispose()
    logger.info("app_stopped")


app = FastAPI(title="Order Service", lifespan=lifespan)

app.include_router(order_router)


@app.get("/health/live")
async def health_live() -> dict:
    return {"status": "ok"}


@app.get("/health/ready")
async def health_ready() -> JSONResponse:
    if app_state["is_shutting_down"]:
        return JSONResponse(status_code=503, content={"status": "shutting_down"})
    return JSONResponse(status_code=200, content={"status": "ready"})


# 구체 타입(OrderNotFoundError)을 상위 타입(OrderError)보다 먼저 등록해 404가 400보다 우선 매칭되도록 한다
@app.exception_handler(OrderNotFoundError)
async def order_not_found_handler(request: Request, exc: OrderNotFoundError) -> JSONResponse:
    return JSONResponse(status_code=404, content=build_error_response(404, exc.code.value, str(exc)))


@app.exception_handler(OrderError)
async def order_error_handler(request: Request, exc: OrderError) -> JSONResponse:
    return JSONResponse(status_code=400, content=build_error_response(400, exc.code.value, str(exc)))


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    messages = [str(e["msg"]) for e in exc.errors()]
    return JSONResponse(
        status_code=422, content=build_error_response(422, "VALIDATION_FAILED", "; ".join(messages))
    )
```

두 번째 도메인이 추가되면 `app.include_router(user_router)` 한 줄을 더하는 것으로 끝난다 — NestJS의 `AppModule.imports`에 대응하는 지점이지만 전용 클래스가 없다. 상세: [bootstrap.md](architecture/bootstrap.md).

---

## 공용 유틸

```python
# src/common/generate_id.py
import uuid


def generate_id() -> str:
    return uuid.uuid4().hex   # 하이픈 제거 32자리 hex — aggregate-id.md
```

```python
# src/common/error_response.py
from pydantic import BaseModel


class ErrorResponse(BaseModel):
    statusCode: int
    code: str
    message: str
    error: str


_STATUS_TEXT = {400: "Bad Request", 404: "Not Found", 422: "Unprocessable Entity", 500: "Internal Server Error"}


def build_error_response(status_code: int, code: str, message: str) -> dict:
    return ErrorResponse(
        statusCode=status_code, code=code, message=message, error=_STATUS_TEXT.get(status_code, "Error")
    ).model_dump()
```

---

## 에러 코드 · 예외 전체 목록

```python
# src/order/domain/error_codes.py
class OrderErrorCode(str, Enum):
    ORDER_NOT_FOUND = "ORDER_NOT_FOUND"
    ORDER_ALREADY_CANCELLED = "ORDER_ALREADY_CANCELLED"
    ORDER_PAID_CANNOT_BE_CANCELLED = "ORDER_PAID_CANNOT_BE_CANCELLED"
    ORDER_MUST_HAVE_AT_LEAST_ONE_ITEM = "ORDER_MUST_HAVE_AT_LEAST_ONE_ITEM"
    INVALID_PRICE = "INVALID_PRICE"
    INVALID_QUANTITY = "INVALID_QUANTITY"
    PAYMENT_METHOD_NOT_FOUND = "PAYMENT_METHOD_NOT_FOUND"
    VALIDATION_FAILED = "VALIDATION_FAILED"   # Pydantic 검증 실패 전용, 고정값
```

`OrderError` 하위 클래스 전체 목록은 위 "도메인 예외 + 에러 코드" 절 참조 — 메시지(`str(exc)`)와 코드(`exc.code`)가 항상 1:1로 존재해야 한다.
