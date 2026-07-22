"""[2] The ABC Repository — must be located only in domain/"""

from __future__ import annotations

from .common import RuleResult, failed, norm, passed, read, rel, skipped


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("repository-abc")
    found = False
    for f in py_files:
        src = read(f)
        if "ABC" not in src and "abstractmethod" not in src:
            continue
        if "Repository" not in src:
            continue
        found = True
        r = rel(root, f)
        if "/domain/" in norm(f):
            result.add(passed(f"{r} (Repository ABC)"))
        else:
            result.add(failed(r, "The ABC Repository must be inside the domain/ package"))
    if not found:
        result.add(skipped("No ABC Repository is defined"))
    return result
