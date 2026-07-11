import os


class RateLimitConfig:
    def __init__(self) -> None:
        self.default_limit = os.getenv("RATE_LIMIT_DEFAULT", "100/minute")
        self.write_limit = os.getenv("RATE_LIMIT_WRITE", "20/minute")
