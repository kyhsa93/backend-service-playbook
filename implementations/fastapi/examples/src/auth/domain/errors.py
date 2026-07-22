from .error_codes import AuthErrorCode


class InvalidTokenError(Exception):
    def __init__(self) -> None:
        super().__init__("Invalid or expired token.")


class AuthError(Exception):
    code: AuthErrorCode


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
