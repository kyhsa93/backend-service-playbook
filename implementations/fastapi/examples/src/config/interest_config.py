import os
from decimal import Decimal


class InterestConfig:
    """The daily interest rate used by the regular interest-payment batch (scheduling.md).
    Since it's not a secret but a tunable operational parameter, it isn't added to the
    fail-fast required-validation set (config/validator.py), the same as
    `rate_limit_config.py` — the app can always start up with just the default (0.01%).
    """

    def __init__(self) -> None:
        self.daily_rate = Decimal(os.getenv("ACCOUNT_INTEREST_DAILY_RATE", "0.0001"))
