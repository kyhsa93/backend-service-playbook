"""[3] Repository 구현체 — infrastructure/ 에만 위치"""
from __future__ import annotations

import re

from .common import RuleResult, failed, norm, passed, read, rel, skipped


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("repository-impl")
    found = False
    for f in py_files:
        src = read(f)
        if "Repository" not in src:
            continue
        if "abstractmethod" in src:
            continue
        if not re.search(r'class\s+\w+Repository\b', src):
            continue
        found = True
        r = rel(root, f)
        if "/infrastructure/" in norm(f):
            result.add(passed(f"{r} (Repository 구현체)"))
        else:
            result.add(failed(r, "Repository 구현체는 infrastructure/ 패키지 안에 있어야 함"))
    if not found:
        result.add(skipped("Repository 구현체 없음"))
    return result
