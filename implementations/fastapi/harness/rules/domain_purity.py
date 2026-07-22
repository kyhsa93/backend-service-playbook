"""[5] domain/ purity — importing FastAPI/SQLAlchemy/aioboto3/a logging library is forbidden

The logging prohibition reflects observability.md's "no logging in the Domain layer"
principle — Domain must import no logger at all for its purity to be preserved
(`src/account/domain/account.py` is the actual example). Both the stdlib `logging` and
`structlog` (which this repository mentions as an alternative) are included in the
blocklist.
"""

from __future__ import annotations

import re

from .common import RuleResult, failed, norm, passed, read, rel, skipped

FORBIDDEN = re.compile(
    r"from fastapi|import fastapi"
    r"|from sqlalchemy|import sqlalchemy"
    r"|from aioboto3|import aioboto3"
    r"|from logging\b|import logging\b"
    r"|from structlog\b|import structlog\b"
)


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("domain-purity")
    found = False
    for f in py_files:
        if "/domain/" not in norm(f):
            continue
        found = True
        src = read(f)
        r = rel(root, f)
        if FORBIDDEN.search(src):
            result.add(failed(r, "A domain/ module must not import fastapi/sqlalchemy/aioboto3/logging/structlog"))
        else:
            result.add(passed(f"{r} (domain purity)"))
    if not found:
        result.add(skipped("No Python file in domain/"))
    return result
