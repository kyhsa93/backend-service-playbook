class InvalidTokenError(Exception):
    def __init__(self) -> None:
        super().__init__("유효하지 않거나 만료된 토큰입니다.")
