from dataclasses import dataclass
from .errors import InvalidPriceError, InvalidQuantityError


@dataclass(frozen=True)
class OrderItem:
    item_id: int
    name: str
    price: int
    quantity: int

    def __post_init__(self) -> None:
        if self.price <= 0:
            raise InvalidPriceError()
        if self.quantity <= 0:
            raise InvalidQuantityError()
