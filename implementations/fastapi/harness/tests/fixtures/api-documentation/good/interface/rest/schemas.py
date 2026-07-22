from pydantic import BaseModel


class ErrorResponse(BaseModel):
    statusCode: int
    code: str
    message: str
    error: str
