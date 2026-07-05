class OrderNotFoundError(Exception):
    def __init__(self, order_id: str) -> None:
        super().__init__(f"order not found: {order_id}")
        self.order_id = order_id


class OrderAlreadyCancelledError(Exception):
    def __init__(self) -> None:
        super().__init__("order already cancelled")


class OrderPaidNotCancellableError(Exception):
    def __init__(self) -> None:
        super().__init__("paid order cannot be cancelled")


class OrderEmptyItemsError(Exception):
    def __init__(self) -> None:
        super().__init__("order must have at least one item")


class InvalidPriceError(Exception):
    def __init__(self) -> None:
        super().__init__("item price must be greater than zero")


class InvalidQuantityError(Exception):
    def __init__(self) -> None:
        super().__init__("item quantity must be greater than zero")
