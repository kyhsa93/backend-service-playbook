from fastapi import APIRouter

from .schemas import ErrorResponse

router = APIRouter(prefix="/orders")


@router.post(
    "/{order_id}/cancel",
    status_code=204,
    responses={
        400: {"model": ErrorResponse, "description": "The order is already cancelled (`ORDER_ALREADY_CANCELLED`)."},
        404: {"model": ErrorResponse, "description": "No order exists with the given `order_id` (`ORDER_NOT_FOUND`)."},
    },
)
async def cancel(order_id: str) -> None:
    return None
