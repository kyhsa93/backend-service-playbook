from fastapi import Request
from fastapi.responses import JSONResponse

from .errors import SomeError


@app.exception_handler(SomeError)
async def some_error_handler(request: Request, exc: SomeError) -> JSONResponse:
    return JSONResponse(
        status_code=400,
        content={
            "statusCode": 400,
            "code": "SOME_ERROR",
            "message": str(exc),
        },
    )
