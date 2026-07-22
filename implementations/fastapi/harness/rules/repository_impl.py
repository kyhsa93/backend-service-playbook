"""[3] The Repository implementation — must be located only in infrastructure/"""

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
        if not re.search(r"class\s+\w+Repository\b", src):
            continue
        found = True
        r = rel(root, f)
        if "/infrastructure/" in norm(f):
            result.add(passed(f"{r} (a Repository implementation)"))
        else:
            result.add(failed(r, "The Repository implementation must be inside the infrastructure/ package"))
    if not found:
        result.add(skipped("No Repository implementation"))
    return result
