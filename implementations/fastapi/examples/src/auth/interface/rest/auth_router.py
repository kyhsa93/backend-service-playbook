from fastapi import APIRouter

from ...infrastructure.jwt_auth_service import JwtAuthService
from .schemas import SignInRequest, SignInResponse

router = APIRouter(prefix="/auth", tags=["Auth"])


@router.post("/sign-in", status_code=201, response_model=SignInResponse)
async def sign_in(body: SignInRequest) -> SignInResponse:
    # 이 저장소에는 별도의 User/자격증명 저장소가 없다 — 자격증명 검증 없이 주어진 user_id로
    # 토큰을 발급한다(nestjs/go/java/kotlin 구현과 동일한 단순화).
    access_token = JwtAuthService().issue_token(body.user_id)
    return SignInResponse(access_token=access_token)
