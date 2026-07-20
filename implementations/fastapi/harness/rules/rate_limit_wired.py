"""[26] rate-limit-wired: slowapi Limiter가 정의만 되고 죽은 코드로 남아있지 않은지
(rate-limiting.md)

rate-limiting.md는 `slowapi`의 `Limiter`를 `src/common/rate_limit.py`에 싱글턴으로 두고,
`main.py`에서 (a) `app.state.limiter = limiter`로 앱에 등록하고 (b) `RateLimitExceeded`
예외 핸들러를 등록하고 (c) `SlowAPIMiddleware`를 미들웨어 체인에 추가하도록 못박는다. 이
셋 중 하나라도 빠지면 `Limiter` 인스턴스가 정의만 되고 실제로는 요청 처리에 관여하지 않는
죽은 코드가 된다. 마지막으로 (d) 적어도 하나의 라우터가 `@limiter.limit(...)`을 실제
엔드포인트에 적용하는지도 확인한다 — 전역 등록만 있고 어디에도 적용되지 않으면 마찬가지로
무의미하다.

이 프로젝트가 애초에 slowapi를 쓰지 않는다면(다른 rate limiting 메커니즘을 쓰거나 아직
구현하지 않았다면) 이 규칙은 skip한다 — S5는 "정의는 됐는데 배선이 안 된" 상태만 잡는
가드이지, rate limiting 자체의 존재를 강제하지 않는다.
"""

from __future__ import annotations

import re

from .common import RuleResult, failed, norm, passed, read, rel, skipped

LIMITER_DEFINITION = re.compile(r"\bLimiter\s*\(")
STATE_REGISTRATION = re.compile(r"\bapp\.state\.limiter\s*=")
EXCEPTION_HANDLER = re.compile(
    r"add_exception_handler\(\s*RateLimitExceeded|@app\.exception_handler\(\s*RateLimitExceeded\s*\)"
)
MIDDLEWARE_REGISTRATION = re.compile(r"add_middleware\(\s*SlowAPIMiddleware\s*\)")
LIMIT_DECORATOR = re.compile(r"@limiter\.limit\(")


def _find_main_py(root: str, py_files: list[str]) -> str | None:
    candidates = [f for f in py_files if norm(f).endswith("/main.py") or norm(f).endswith("main.py")]
    for f in candidates:
        if "FastAPI(" in read(f):
            return f
    return candidates[0] if candidates else None


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("rate-limit-wired")

    limiter_defined = any(LIMITER_DEFINITION.search(read(f)) for f in py_files)
    if not limiter_defined:
        result.add(skipped("slowapi Limiter 정의가 없음 — rate limiting 미구현 또는 다른 메커니즘 사용"))
        return result

    main_py = _find_main_py(root, py_files)
    if main_py is None:
        result.add(failed("main.py", "Limiter는 정의되어 있으나 앱 진입점(main.py)을 찾을 수 없음"))
        return result

    label = rel(root, main_py)
    src = read(main_py)

    if STATE_REGISTRATION.search(src):
        result.add(passed(f"{label} (app.state.limiter 등록)"))
    else:
        result.add(
            failed(
                label,
                "Limiter가 정의만 되고 app.state.limiter로 앱에 등록되지 않음 — 죽은 코드(rate-limiting.md)",
            )
        )

    if EXCEPTION_HANDLER.search(src):
        result.add(passed(f"{label} (RateLimitExceeded 예외 핸들러 등록)"))
    else:
        result.add(
            failed(
                label,
                "RateLimitExceeded 예외 핸들러가 등록되지 않음 — 제한 초과 시 429 대신 처리되지"
                " 않은 예외가 발생함(rate-limiting.md)",
            )
        )

    if MIDDLEWARE_REGISTRATION.search(src):
        result.add(passed(f"{label} (SlowAPIMiddleware 등록)"))
    else:
        result.add(
            failed(
                label,
                "SlowAPIMiddleware가 미들웨어 체인에 추가되지 않음 — 표준 응답 헤더"
                "(X-RateLimit-*)와 자동 429 처리가 적용되지 않음(rate-limiting.md)",
            )
        )

    applied = any(LIMIT_DECORATOR.search(read(f)) for f in py_files if norm(f) != norm(main_py))
    if applied:
        result.add(passed("@limiter.limit(...) 이 최소 1개 라우트에 적용됨"))
    else:
        result.add(
            failed(
                "interface/rest/*",
                "Limiter가 정의·전역 등록만 되고 어떤 라우트에도 @limiter.limit(...)이 적용되지"
                " 않음 — 죽은 코드(rate-limiting.md)",
            )
        )

    return result
