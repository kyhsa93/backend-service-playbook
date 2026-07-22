from .error_codes import AuthErrorCode


class AuthError(Exception):
    code: AuthErrorCode


class InvalidTokenError(AuthError):
    code = AuthErrorCode.INVALID_TOKEN

    def __init__(self) -> None:
        # Also raised (via dependencies.py's Bearer-scheme wrapper) when the
        # `Authorization` header is missing or malformed, not only for a signature/
        # expiration failure — every "the client isn't authenticated" case funnels into
        # this one exception so the API always responds with the same 401 shape.
        super().__init__("Invalid or expired token.")


class InvalidCredentialsError(AuthError):
    code = AuthErrorCode.INVALID_CREDENTIALS

    def __init__(self) -> None:
        # Responds with the same message whether the username doesn't exist or the password
        # doesn't match — so an existing username can't be guessed (prevents user
        # enumeration). See sign_in_handler.py.
        super().__init__("Incorrect username or password.")


class UserIdAlreadyExistsError(AuthError):
    code = AuthErrorCode.USER_ID_ALREADY_EXISTS

    def __init__(self) -> None:
        super().__init__("This username is already in use.")
