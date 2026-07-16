from .error_codes import AuthErrorCode


class InvalidTokenError(Exception):
    def __init__(self) -> None:
        super().__init__("유효하지 않거나 만료된 토큰입니다.")


class AuthError(Exception):
    code: AuthErrorCode


class InvalidCredentialsError(AuthError):
    code = AuthErrorCode.INVALID_CREDENTIALS

    def __init__(self) -> None:
        # 아이디 미존재/비밀번호 불일치를 동일한 메시지로 응답한다 — 존재하는 아이디를
        # 추측 가능하게 만들지 않기 위함(user enumeration 방지). sign_in_handler.py 참고.
        super().__init__("아이디 또는 비밀번호가 올바르지 않습니다.")


class UserIdAlreadyExistsError(AuthError):
    code = AuthErrorCode.USER_ID_ALREADY_EXISTS

    def __init__(self) -> None:
        super().__init__("이미 사용 중인 아이디입니다.")
