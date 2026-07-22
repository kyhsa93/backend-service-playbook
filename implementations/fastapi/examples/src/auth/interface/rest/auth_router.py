from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from ....common.error_response import ErrorResponse
from ....common.rate_limit import limiter, rate_limit_config
from ....database import get_session
from ...application.command.sign_in_handler import SignInCommand, SignInHandler
from ...application.command.sign_up_handler import SignUpCommand, SignUpHandler
from ...domain.repository import CredentialRepository
from ...infrastructure.jwt_auth_service import JwtAuthService
from ...infrastructure.persistence.credential_repository import SqlAlchemyCredentialRepository
from ...infrastructure.security.bcrypt_password_hasher import BcryptPasswordHasher
from .schemas import SignInRequest, SignInResponse, SignUpRequest

router = APIRouter(prefix="/auth", tags=["Auth"])


def _credential_repo(session: AsyncSession = Depends(get_session)) -> CredentialRepository:
    return SqlAlchemyCredentialRepository(session)


@router.post(
    "/sign-up",
    status_code=201,
    summary="Register a new user",
    description="Creates a new credential (username + password) that can then be used to sign in.",
    responses={
        400: {
            "model": ErrorResponse,
            "description": "The username is already in use (`USER_ID_ALREADY_EXISTS`).",
        },
        422: {
            "model": ErrorResponse,
            "description": (
                "Request validation failed (`VALIDATION_FAILED`) — e.g. a password shorter than 8 characters."
            ),
        },
    },
)
@limiter.limit(rate_limit_config.write_limit)
async def sign_up(
    request: Request,
    body: SignUpRequest,
    repo: CredentialRepository = Depends(_credential_repo),
) -> None:
    await SignUpHandler(repo, BcryptPasswordHasher()).execute(
        SignUpCommand(user_id=body.user_id, password=body.password)
    )


@router.post(
    "/sign-in",
    status_code=201,
    response_model=SignInResponse,
    summary="Sign in",
    description=(
        "Verifies the username and password, and issues a bearer access token to use as "
        "`Authorization: Bearer <token>` on every other endpoint."
    ),
    responses={
        401: {
            "model": ErrorResponse,
            "description": (
                "The username or password is incorrect (`INVALID_CREDENTIALS`). The same message and code "
                "are returned whether the username doesn't exist or the password is wrong, to avoid leaking "
                "which one was wrong."
            ),
        },
        422: {
            "model": ErrorResponse,
            "description": "Request validation failed (`VALIDATION_FAILED`) — e.g. a missing username or password.",
        },
    },
)
@limiter.limit(rate_limit_config.write_limit)
async def sign_in(
    request: Request,
    body: SignInRequest,
    repo: CredentialRepository = Depends(_credential_repo),
) -> SignInResponse:
    access_token = await SignInHandler(repo, BcryptPasswordHasher(), JwtAuthService()).execute(
        SignInCommand(user_id=body.user_id, password=body.password)
    )
    return SignInResponse(access_token=access_token)
