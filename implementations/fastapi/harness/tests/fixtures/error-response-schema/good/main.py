from fastapi import Request
from fastapi.responses import JSONResponse

from .error_response import build_error_response
from .errors import SomeError


@app.exception_handler(SomeError)
async def some_error_handler(request: Request, exc: SomeError) -> JSONResponse:
    return JSONResponse(status_code=400, content=build_error_response(400, "SOME_ERROR", str(exc)))
