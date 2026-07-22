from fastapi import APIRouter

router = APIRouter(prefix="/orders")


@router.post(
    "/{order_id}/cancel",
    status_code=204,
    summary="Cancel an order",
    description="Cancels an order that has not yet been paid.",
)
async def cancel(order_id: str) -> None:
    return None
