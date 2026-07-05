from pydantic import BaseModel


class ItemInputSchema(BaseModel):
    item_id: int
    name: str
    price: int
    quantity: int


class CreateOrderRequest(BaseModel):
    user_id: str
    items: list[ItemInputSchema]


class CancelOrderRequest(BaseModel):
    reason: str


class OrderSummaryResponse(BaseModel):
    order_id: str
    status: str
    total_amount: int


class GetOrdersResponse(BaseModel):
    orders: list[OrderSummaryResponse]
    total_count: int


class GetOrderResponse(BaseModel):
    order_id: str
    status: str
    total_amount: int
