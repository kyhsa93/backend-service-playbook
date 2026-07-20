"""[5] domain/ 순수성 — FastAPI/SQLAlchemy/aioboto3/로깅 라이브러리 import 금지

로깅 금지는 observability.md의 "Domain 레이어에서 로깅하지 않는다" 원칙을 반영한다 — Domain은
어떤 로거도 import하지 않아야 순수성이 유지된다(`src/account/domain/account.py`가 실제
예시). stdlib `logging`과 이 저장소가 대안으로 언급하는 `structlog` 둘 다 블록리스트에
포함한다.
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
            result.add(failed(r, "domain/ 모듈에 fastapi/sqlalchemy/aioboto3/logging/structlog import 금지"))
        else:
            result.add(passed(f"{r} (domain 순수성)"))
    if not found:
        result.add(skipped("domain/ Python 파일 없음"))
    return result
