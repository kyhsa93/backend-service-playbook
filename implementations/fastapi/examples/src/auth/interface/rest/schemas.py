from pydantic import BaseModel, Field


class SignUpRequest(BaseModel):
    user_id: str
    password: str = Field(min_length=8)


class SignInRequest(BaseModel):
    user_id: str
    password: str


class SignInResponse(BaseModel):
    access_token: str
