from slowapi import Limiter
from slowapi.util import get_remote_address

from ..config.rate_limit_config import RateLimitConfig

# main.py와 각 도메인 router가 공유하는 단일 Limiter 인스턴스 — router에서
# `from ....common.rate_limit import limiter`로 가져와 `@limiter.limit(...)`을 적용한다.
# main.py에서 직접 정의하면 router가 main을 import해야 해서 순환 import가 발생하므로
# (module-pattern.md) 공유 인프라 위치인 common/에 둔다.
rate_limit_config = RateLimitConfig()

limiter = Limiter(key_func=get_remote_address, default_limits=[rate_limit_config.default_limit])
