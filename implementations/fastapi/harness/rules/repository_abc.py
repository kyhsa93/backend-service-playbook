"""[2] ABC Repository — domain/ 에만 위치"""

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
            result.add(failed(r, "ABC Repository는 domain/ 패키지 안에 있어야 함"))
    if not found:
        result.add(skipped("ABC Repository 정의 없음"))
    return result
