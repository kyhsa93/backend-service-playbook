from pydantic import BaseModel


class SignInRequest(BaseModel):
    user_id: str


class SignInResponse(BaseModel):
    access_token: str
