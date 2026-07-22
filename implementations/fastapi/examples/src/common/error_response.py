from pydantic import BaseModel, Field


class ErrorResponse(BaseModel):
    statusCode: int = Field(description="The HTTP status code.", examples=[400])
    code: str = Field(
        description=(
            "A stable, machine-readable error code the client can branch on. Unlike "
            "`message`, this never changes wording or gets translated."
        ),
        examples=["VALIDATION_FAILED"],
    )
    message: str = Field(
        description="A human-readable description of the error.",
        examples=["Account not found."],
    )
    error: str = Field(description="The standard HTTP status text for `statusCode`.", examples=["Bad Request"])


_STATUS_TEXT = {
    400: "Bad Request",
    401: "Unauthorized",
    404: "Not Found",
    422: "Unprocessable Entity",
    500: "Internal Server Error",
}


def build_error_response(status_code: int, code: str, message: str) -> dict:
    return ErrorResponse(
        statusCode=status_code, code=code, message=message, error=_STATUS_TEXT.get(status_code, "Error")
    ).model_dump()
