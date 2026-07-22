# Practical Implementation Template

An example of one full domain (Order) implemented with this architecture. Copy this template as a starting point when adding a new domain.

> This document shows the **target state**. The Repository method naming (`find<Noun>s`/`save<Noun>`/`delete<Noun>`) is already reflected identically to this target in `examples/src/account/`·`card/`·`payment/` — when writing a new domain, refer to this document together with `examples/`. Error codes/Outbox/authentication are also already reflected in `examples/`.

---

## Directory structure

```
src/
  config/
    database_config.py             ← DB configuration (pydantic-settings)
    jwt_config.py                  ← JWT configuration (pydantic-settings)
    validator.py                   ← fail-fast validation at startup
  common/
    generate_id.py                 ← an ID-generation util based on uuid.uuid4().hex
    error_response.py               ← the standard statusCode/code/message/error response builder
    correlation.py                  ← a contextvars-based Correlation ID
  auth/
    application/
      service/
        auth_service.py             ← AuthService(ABC) — the token issuance/verification interface
    infrastructure/
      jwt_auth_service.py           ← JwtAuthService(AuthService) — the python-jose implementation
    interface/
      rest/
        dependencies.py             ← get_current_user() — shared across routers via Depends
  order/
    domain/
      order.py                     ← the Aggregate Root
      order_item.py                ← a Value Object
      order_status.py              ← a domain enum
      events.py                    ← a collection of Domain Events
      errors.py                    ← the domain exception hierarchy
      error_codes.py                ← the error-code enum (1:1 with the exceptions)
      repository.py                ← OrderRepository(ABC), PaymentRepository(ABC)
    application/
      adapter/
        payment_adapter.py          ← the interface for calling an external domain (ABC)
      service/
        crypto_service.py           ← a technical-infrastructure interface (ABC)
      command/
        create_order_handler.py     ← CreateOrderCommand + CreateOrderHandler
        cancel_order_handler.py     ← CancelOrderCommand + CancelOrderHandler
        delete_order_handler.py     ← DeleteOrderCommand + DeleteOrderHandler
      query/
        result.py                   ← GetOrderResult, GetOrdersResult, etc.
        get_order_handler.py        ← GetOrderQuery + GetOrderHandler
        get_orders_handler.py       ← GetOrdersQuery + GetOrdersHandler
    infrastructure/
      persistence/
        order_repository.py         ← Base, OrderModel, OrderItemModel, SqlAlchemyOrderRepository
        outbox_model.py             ← OutboxModel — the table Domain Events are loaded into
      payment_adapter_impl.py       ← InProcessPaymentAdapter(PaymentAdapter)
      crypto_service_impl.py        ← AesCryptoService(CryptoService)
      scheduling/
        order_cleanup_scheduler.py  ← APScheduler — Cron (optional, for when cleaning up expired orders, etc. is needed)
    interface/
      rest/
        order_router.py             ← APIRouter, Depends assembly, calls the Handler
        schemas.py                  ← Pydantic request/response models
  database.py                       ← the engine/session factory, the get_session() dependency
main.py                              ← creates the FastAPI app, lifespan, registers routers/exception handlers
```

---

## The Domain layer

### The Aggregate Root

Since the Aggregate Root's state changes and it must guard its own invariants, it's written as a plain class, not a `@dataclass` — see [tactical-ddd.md](architecture/tactical-ddd.md).

```python
# domain/order.py — framework-agnostic
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
            order_id=generate_id(),   # 32-character hex, hyphens removed — aggregate-id.md
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

### A Value Object

```python
# domain/order_item.py — an immutable object
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

The `__eq__` that a `frozen=True` dataclass auto-generates handles attribute-based equality comparison, so a separate `equals()` method is never created.

### A domain enum

```python
# domain/order_status.py
from enum import Enum


class OrderStatus(str, Enum):
    PENDING = "pending"
    PAID = "paid"
    CANCELLED = "cancelled"
```

### Domain Events

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

### Domain exceptions + error codes

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
        super().__init__(f"Order not found: {order_id}")
        self.order_id = order_id


class OrderAlreadyCancelledError(OrderError):
    code = OrderErrorCode.ORDER_ALREADY_CANCELLED

    def __init__(self) -> None:
        super().__init__("The order is already cancelled.")


class OrderPaidCannotBeCancelledError(OrderError):
    code = OrderErrorCode.ORDER_PAID_CANNOT_BE_CANCELLED

    def __init__(self) -> None:
        super().__init__("A paid order cannot be cancelled.")


class OrderMustHaveAtLeastOneItemError(OrderError):
    code = OrderErrorCode.ORDER_MUST_HAVE_AT_LEAST_ONE_ITEM

    def __init__(self) -> None:
        super().__init__("An order must have at least one item.")


class InvalidPriceError(OrderError):
    code = OrderErrorCode.INVALID_PRICE

    def __init__(self) -> None:
        super().__init__("The item price must be greater than 0.")


class InvalidQuantityError(OrderError):
    code = OrderErrorCode.INVALID_QUANTITY

    def __init__(self) -> None:
        super().__init__("The quantity must be greater than 0.")


class PaymentMethodNotFoundError(OrderError):
    code = OrderErrorCode.PAYMENT_METHOD_NOT_FOUND

    def __init__(self, order_id: str) -> None:
        super().__init__(f"Payment info not found: {order_id}")
```

### The Repository interface

```python
# domain/repository.py — the ABC
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
    async def save_order(self, order: Order) -> None: ...

    @abstractmethod
    async def delete_order(self, order_id: str) -> None: ...


class PaymentRepository(ABC):
    @abstractmethod
    async def find_payment_methods(self, order_id: str, page: int, take: int) -> tuple[list["PaymentMethod"], int]: ...

    @abstractmethod
    async def delete_payment_methods(self, order_id: str) -> None: ...
```

The lookup method is unified into a single `find_orders`, with a single-item lookup obtained via `take=1` followed by indexing — see "the form matching the root convention" in [repository-pattern.md](architecture/repository-pattern.md).

---

## The Application layer

### A Command Handler

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
        await self._repo.save_order(order)   # even loading into the Outbox is handled inside save_order()
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
        # Look up the order — the find_orders(take=1) + indexing pattern
        orders, _ = await self._repo.find_orders(page=0, take=1, order_id=cmd.order_id)
        order = orders[0] if orders else None
        if order is None:
            raise OrderNotFoundError(cmd.order_id)

        # Business rules are validated inside the Aggregate
        order.cancel(cmd.reason)

        # Writes spanning multiple Repositories — bundled into the same AsyncSession (the same Depends(get_session))
        await self._payment_repo.delete_payment_methods(order.order_id)
        await self._repo.save_order(order)   # saves the Aggregate state + pull_events() into the Outbox together
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
        await self._repo.delete_order(cmd.order_id)
```

**Flow:** look up the Aggregate from the Repository → call the Aggregate's domain method → save via the Repository. The Handler itself has no business rules and delegates to the Aggregate — see [layer-architecture.md](architecture/layer-architecture.md).

### A Query Handler + Result

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

A QueryHandler never returns the domain Aggregate directly — it converts it into a Result dataclass — see [api-response.md](architecture/api-response.md).

### An Adapter interface (cross-domain)

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

Its methods are defined only in the shape the calling side (Order) needs — the target domain's (User's) full API is never exposed. Details: [cross-domain.md](architecture/cross-domain.md).

### A Technical Service interface

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

## The Infrastructure layer

### The Repository implementation

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

    id: Mapped[str] = mapped_column(CHAR(32), primary_key=True)   # 32-character hex, hyphens removed — aggregate-id.md
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

        # A dynamic filter — applied only when a value is present, applied identically to count
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

        # Convert the DB model → the domain Aggregate
        return [self._to_domain(row) for row in rows], count

    async def save_order(self, order: Order) -> None:
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

        # Domain Event → Outbox — loaded together into the same session (the same transaction)
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

    async def delete_order(self, order_id: str) -> None:
        # cascade soft delete — child Entities first
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

### The Adapter implementation

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

Within the same process (a monolith), start with an in-process implementation that directly calls the target domain's Handler. Once the service is split out, swap it for an `HttpPaymentAdapter` (an internal HTTP client) implementing the same ABC — the calling side's (Order's) code doesn't change. Details: [cross-domain.md](architecture/cross-domain.md).

### The Technical Service implementation

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

### The Scheduler (optional — for when Cron is needed)

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
                # Clean up expired orders — delegate the business logic to a Handler, or do only the bare minimum here directly
                ...
        except Exception:
            logger.exception("order_cleanup_scheduler_failed")   # log explicitly rather than swallowing the exception

    scheduler.start()
    return scheduler
```

The Scheduler never executes business logic directly — it only serves as a trigger. Details: [scheduling.md](architecture/scheduling.md).

---

## The Interface layer

### The Pydantic schema

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
    items: list[OrderItemRequest] = Field(..., min_length=1, description="The list of order items")


class CancelOrderRequest(BaseModel):
    reason: str = Field(..., min_length=1, description="The cancellation reason")
    refund_amount: int | None = Field(None, ge=0, description="The refund amount")


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
    orders: list[OrderSummaryResponse]   # the plural of the domain object name — result/data is forbidden
    count: int
```

### The Router

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

The router-level `dependencies=[Depends(get_current_user)]` applies authentication to every route in one shot — never redeclared on each individual route. Details: [authentication.md](architecture/authentication.md).

---

## Wiring (a `Depends` factory = NestJS's `{ provide, useClass }`)

Since FastAPI has no dedicated DI container, the ABC ↔ implementation binding is handled by the factory functions themselves in the router file (`_repo`, `_payment_adapter`, etc.) — details: [module-pattern.md](architecture/module-pattern.md).

```python
# interface/rest/order_router.py — when wiring even an Adapter/Technical Service
def _payment_adapter(session: AsyncSession = Depends(get_session)) -> PaymentAdapter:
    return InProcessPaymentAdapter(get_user_handler=...)


def _crypto_service() -> CryptoService:
    return AesCryptoService(key=os.environ["CRYPTO_KEY"].encode())
```

### main.py — composing routers + registering exception handlers

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
    validate_env()   # fail-fast — the process exits right here if a required environment variable is missing
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)   # local/test only — production uses Alembic
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


# Register the concrete type (OrderNotFoundError) before its supertype (OrderError) so 404 matches before 400
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

Once a second domain is added, it's as simple as adding one more line, `app.include_router(user_router)` — the point corresponding to NestJS's `AppModule.imports`, except there's no dedicated class. Details: [bootstrap.md](architecture/bootstrap.md).

---

## Shared utilities

```python
# src/common/generate_id.py
import uuid


def generate_id() -> str:
    return uuid.uuid4().hex   # 32-character hex, hyphens removed — aggregate-id.md
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

## The full list of error codes and exceptions

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
    VALIDATION_FAILED = "VALIDATION_FAILED"   # reserved exclusively for Pydantic validation failures, a fixed value
```
