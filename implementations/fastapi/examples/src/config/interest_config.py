import os
from decimal import Decimal


class InterestConfig:
    """정기 이자 지급 배치(scheduling.md)가 쓰는 일일 이자율. 시크릿이 아니라 튜닝 가능한
    운영 파라미터라 `rate_limit_config.py`와 동일하게 fail-fast 필수 검증 대상(config/validator.py)
    에는 넣지 않는다 — 기본값(0.01%)만으로도 항상 기동 가능하다.
    """

    def __init__(self) -> None:
        self.daily_rate = Decimal(os.getenv("ACCOUNT_INTEREST_DAILY_RATE", "0.0001"))
