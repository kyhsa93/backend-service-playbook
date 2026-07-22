from slowapi import Limiter
from slowapi.util import get_remote_address

from ..config.rate_limit_config import RateLimitConfig

# A single Limiter instance shared by main.py and each domain router — a router imports
# it via `from ....common.rate_limit import limiter` and applies `@limiter.limit(...)`.
# Defining it directly in main.py would create a circular import, since the router would
# then need to import main (module-pattern.md), so it lives in common/, the shared
# infrastructure location.
rate_limit_config = RateLimitConfig()

limiter = Limiter(key_func=get_remote_address, default_limits=[rate_limit_config.default_limit])
