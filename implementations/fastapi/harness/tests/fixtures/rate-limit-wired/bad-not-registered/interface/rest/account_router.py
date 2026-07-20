from fastapi import APIRouter, Request

from ...common.rate_limit import limiter

router = APIRouter(prefix="/accounts")


@router.post("/{account_id}/withdraw")
@limiter.limit("10/minute")
async def withdraw(request: Request, account_id: str) -> dict:
    return {"ok": True}
