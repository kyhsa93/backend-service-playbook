"""[12] CQRS — application/query/ must never reference a write-capable Repository
(cqrs-pattern.md)

A QueryHandler must depend only on a read-only Query interface (e.g. AccountQuery). If a
write-capable Repository (e.g. AccountRepository, which includes save()) is injected
as-is, a write method becomes accessible even at the compile/type level, making the CQRS
separation meaningless — this rule ports the same intent as the nestjs harness's
evaluators/rules/cqrs-pattern.evaluator.ts.
"""

from __future__ import annotations

from .common import RuleResult, failed, norm, passed, read, rel, skipped


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("cqrs-pattern")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/application/query/" not in fn:
            continue
        found = True
        r = rel(root, f)
        src = read(f)
        if "Repository" in src:
            result.add(
                failed(
                    r,
                    "application/query/ must never reference a write-capable Repository"
                    " — it must depend only on a read-only Query interface (cqrs-pattern.md)",
                )
            )
        else:
            result.add(passed(f"{r} (confirmed no Repository reference)"))
    if not found:
        result.add(skipped("No application/query/ file"))
    return result
