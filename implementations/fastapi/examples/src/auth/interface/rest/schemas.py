from pydantic import BaseModel, Field


class SignUpRequest(BaseModel):
    user_id: str = Field(description="The desired username. Must be unique across all credentials.")
    password: str = Field(min_length=8, description="The account password. Must be at least 8 characters.")


class SignInRequest(BaseModel):
    user_id: str = Field(description="The username to sign in with.")
    password: str = Field(description="The password to verify against the stored hash.")


class SignInResponse(BaseModel):
    access_token: str = Field(
        description="A JWT bearer access token. Send it as `Authorization: Bearer <token>` on every other endpoint."
    )
