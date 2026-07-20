from pydantic import BaseModel


class ErrorResponse(BaseModel):
    statusCode: int
    code: str
    message: str
    error: str


def build_error_response(status_code: int, code: str, message: str) -> dict:
    return ErrorResponse(statusCode=status_code, code=code, message=message, error="Bad Request").model_dump()
