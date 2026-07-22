"""[1] File-name snake_case check"""

from __future__ import annotations

import os
import re

from .common import RuleResult, failed, passed, skipped

SNAKE_CASE = re.compile(r"^[a-z][a-z0-9]*(_[a-z0-9]+)*\.py$")


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("file-naming")
    if not py_files:
        result.add(skipped("No Python file"))
        return result
    for f in py_files:
        name = os.path.basename(f)
        r = os.path.relpath(f, root)
        if SNAKE_CASE.match(name):
            result.add(passed(r))
        else:
            result.add(failed(r, "The file name must be snake_case.py"))
    return result
