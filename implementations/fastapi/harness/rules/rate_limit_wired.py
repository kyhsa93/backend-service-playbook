"""[26] rate-limit-wired: whether the slowapi Limiter is only defined and left as dead code
(rate-limiting.md)

rate-limiting.md pins down that `slowapi`'s `Limiter` is kept as a singleton in
`src/common/rate_limit.py`, and in `main.py` it's (a) registered on the app via
`app.state.limiter = limiter`, (b) has a `RateLimitExceeded` exception handler registered,
and (c) has `SlowAPIMiddleware` added to the middleware chain. If even one of these three
is missing, the `Limiter` instance is only defined and becomes dead code that doesn't
actually participate in request handling. Finally, it also confirms (d) whether at least
one router applies `@limiter.limit(...)` to an actual endpoint — if it's only registered
globally and applied nowhere, it's equally meaningless.

If this project doesn't use slowapi at all in the first place (using a different rate-
limiting mechanism, or not implemented yet), this rule is skipped — this check is a guard
that only catches the state of "defined but not wired up," and doesn't mandate the
existence of rate limiting itself.
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
        result.add(
            skipped("No slowapi Limiter defined — rate limiting isn't implemented, or a different mechanism is used")
        )
        return result

    main_py = _find_main_py(root, py_files)
    if main_py is None:
        result.add(failed("main.py", "A Limiter is defined, but the app entry point (main.py) can't be found"))
        return result

    label = rel(root, main_py)
    src = read(main_py)

    if STATE_REGISTRATION.search(src):
        result.add(passed(f"{label} (app.state.limiter is registered)"))
    else:
        result.add(
            failed(
                label,
                "The Limiter is only defined and isn't registered on the app via app.state.limiter"
                " — dead code (rate-limiting.md)",
            )
        )

    if EXCEPTION_HANDLER.search(src):
        result.add(passed(f"{label} (a RateLimitExceeded exception handler is registered)"))
    else:
        result.add(
            failed(
                label,
                "No RateLimitExceeded exception handler is registered — once the limit is"
                " exceeded, an unhandled exception occurs instead of 429 (rate-limiting.md)",
            )
        )

    if MIDDLEWARE_REGISTRATION.search(src):
        result.add(passed(f"{label} (SlowAPIMiddleware is registered)"))
    else:
        result.add(
            failed(
                label,
                "SlowAPIMiddleware isn't added to the middleware chain — the standard"
                " response headers (X-RateLimit-*) and automatic 429 handling aren't applied (rate-limiting.md)",
            )
        )

    applied = any(LIMIT_DECORATOR.search(read(f)) for f in py_files if norm(f) != norm(main_py))
    if applied:
        result.add(passed("@limiter.limit(...) is applied to at least 1 route"))
    else:
        result.add(
            failed(
                "interface/rest/*",
                "The Limiter is only defined and registered globally, with @limiter.limit(...)"
                " applied to no route — dead code (rate-limiting.md)",
            )
        )

    return result
