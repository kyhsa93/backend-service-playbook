from fastapi import APIRouter, Request

router = APIRouter(prefix="/accounts")


@router.post("/{account_id}/withdraw")
async def withdraw(request: Request, account_id: str) -> dict:
    return {"ok": True}
