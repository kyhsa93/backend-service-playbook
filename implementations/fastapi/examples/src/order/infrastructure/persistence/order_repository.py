from datetime import datetime

from sqlalchemy import delete, select, update, func
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from ...domain.order import Order, ItemInput, OrderStatus
from ...domain.order_item import OrderItem
from ...domain.repository import OrderRepository


class Base(DeclarativeBase):
    pass


class OrderModel(Base):
    __tablename__ = "orders"

    order_id: Mapped[str] = mapped_column(primary_key=True)
    user_id: Mapped[str]
    status: Mapped[str]
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(default=datetime.utcnow, onupdate=datetime.utcnow)
    deleted_at: Mapped[datetime | None] = mapped_column(nullable=True, default=None)


class OrderItemModel(Base):
    __tablename__ = "order_items"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    order_id: Mapped[str]
    item_id: Mapped[int]
    name: Mapped[str]
    price: Mapped[int]
    quantity: Mapped[int]
    deleted_at: Mapped[datetime | None] = mapped_column(nullable=True, default=None)


class SqlAlchemyOrderRepository(OrderRepository):

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def find_by_id(self, order_id: str) -> Order | None:
        stmt = select(OrderModel).where(
            OrderModel.order_id == order_id,
            OrderModel.deleted_at.is_(None),
        )
        row = (await self._session.execute(stmt)).scalar_one_or_none()
        if row is None:
            return None
        items = await self._load_items(order_id)
        return self._to_domain(row, items)

    async def find_all(
        self,
        page: int,
        take: int,
        user_id: str | None = None,
        status: list[str] | None = None,
    ) -> tuple[list[Order], int]:
        stmt = select(OrderModel).where(OrderModel.deleted_at.is_(None))
        count_stmt = select(func.count()).select_from(OrderModel).where(OrderModel.deleted_at.is_(None))

        if user_id:
            stmt = stmt.where(OrderModel.user_id == user_id)
            count_stmt = count_stmt.where(OrderModel.user_id == user_id)
        if status:
            stmt = stmt.where(OrderModel.status.in_(status))
            count_stmt = count_stmt.where(OrderModel.status.in_(status))

        total = (await self._session.execute(count_stmt)).scalar_one()
        rows = (await self._session.execute(
            stmt.order_by(OrderModel.order_id.desc()).offset(page * take).limit(take)
        )).scalars().all()

        orders = []
        for row in rows:
            items = await self._load_items(row.order_id)
            orders.append(self._to_domain(row, items))

        return orders, total

    async def save(self, order: Order) -> None:
        existing = await self._session.get(OrderModel, order.order_id)
        if existing:
            existing.status = order.status
            existing.updated_at = datetime.utcnow()
        else:
            self._session.add(OrderModel(
                order_id=order.order_id,
                user_id=order.user_id,
                status=order.status,
            ))
            for item in order.items:
                self._session.add(OrderItemModel(
                    order_id=order.order_id,
                    item_id=item.item_id,
                    name=item.name,
                    price=item.price,
                    quantity=item.quantity,
                ))
        await self._session.flush()

    async def delete(self, order_id: str) -> None:
        now = datetime.utcnow()
        await self._session.execute(
            update(OrderItemModel)
            .where(OrderItemModel.order_id == order_id, OrderItemModel.deleted_at.is_(None))
            .values(deleted_at=now)
        )
        await self._session.execute(
            update(OrderModel)
            .where(OrderModel.order_id == order_id, OrderModel.deleted_at.is_(None))
            .values(deleted_at=now)
        )

    async def _load_items(self, order_id: str) -> list[OrderItem]:
        stmt = select(OrderItemModel).where(
            OrderItemModel.order_id == order_id,
            OrderItemModel.deleted_at.is_(None),
        )
        rows = (await self._session.execute(stmt)).scalars().all()
        return [OrderItem(r.item_id, r.name, r.price, r.quantity) for r in rows]

    def _to_domain(self, row: OrderModel, items: list[OrderItem]) -> Order:
        return Order(
            order_id=row.order_id,
            user_id=row.user_id,
            items=items,
            status=row.status,  # type: ignore[arg-type]
        )
