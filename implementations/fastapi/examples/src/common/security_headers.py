from __future__ import annotations

from fastapi import Request
from starlette.responses import Response


def apply_security_headers(request: Request, response: Response) -> None:
    """Sets baseline security headers on every response — the same four headers a plain
    ASGI/Starlette app has none of by default.

    `Strict-Transport-Security` is conditional: it only makes sense once the client has
    actually reached this response over HTTPS. Locally/in tests, `app` is served over plain
    HTTP directly, and `request.url.scheme` reflects that correctly. In production this
    process sits behind a TLS-terminating load balancer, so the connection FastAPI itself
    sees is plain HTTP even though the client used HTTPS — the LB is trusted to set
    `X-Forwarded-Proto: https` in that case (the same reverse-proxy trust boundary
    `APP_ENV=production` already assumes elsewhere, e.g. main.py's Secrets Manager branch).
    """
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "strict-origin-when-cross-origin"

    is_https = request.url.scheme == "https" or request.headers.get("x-forwarded-proto", "").lower() == "https"
    if is_https:
        response.headers["Strict-Transport-Security"] = "max-age=63072000; includeSubDomains"
