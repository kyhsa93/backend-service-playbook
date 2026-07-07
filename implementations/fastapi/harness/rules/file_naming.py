"""[1] 파일명 snake_case 검사"""
from __future__ import annotations

import os
import re

from .common import RuleResult, failed, passed, skipped

SNAKE_CASE = re.compile(r'^[a-z][a-z0-9]*(_[a-z0-9]+)*\.py$')


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("file-naming")
    if not py_files:
        result.add(skipped("Python 파일 없음"))
        return result
    for f in py_files:
        name = os.path.basename(f)
        r = os.path.relpath(f, root)
        if SNAKE_CASE.match(name):
            result.add(passed(r))
        else:
            result.add(failed(r, "파일명은 snake_case.py 여야 함"))
    return result
