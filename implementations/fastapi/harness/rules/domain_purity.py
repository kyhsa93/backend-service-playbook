"""[5] domain/ 순수성 — FastAPI/SQLAlchemy/aioboto3 import 금지"""
from __future__ import annotations

import re

from .common import RuleResult, failed, norm, passed, read, rel, skipped

FORBIDDEN = re.compile(r'from fastapi|import fastapi|from sqlalchemy|import sqlalchemy|from aioboto3|import aioboto3')


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
            result.add(failed(r, "domain/ 모듈에 fastapi/sqlalchemy/aioboto3 import 금지"))
        else:
            result.add(passed(f"{r} (domain 순수성)"))
    if not found:
        result.add(skipped("domain/ Python 파일 없음"))
    return result
