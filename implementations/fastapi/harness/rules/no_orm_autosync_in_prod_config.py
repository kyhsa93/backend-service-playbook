"""[29] no-orm-autosync-in-prod-config: calling `Base.metadata.create_all(...)` in an app
bootstrap path is forbidden (persistence.md "Migrations — managed with Alembic")

persistence.md pins down that `create_all` only creates a table when it doesn't exist and
can't detect adding/changing/removing a column on an existing table, so it can't be used
for a production schema change — the schema must be managed only via an Alembic migration
(`alembic upgrade head`). `create_all` is allowed only in a local development/test
environment (a testcontainers fixture that creates a fresh DB on every run, etc.).

Since `collect_py_files` in `harness/rules/common.py` already excludes `test_*.py`/
`conftest.py`/`migrations/` (an Alembic-only directory) from the `py_files` list this rule
receives, if `<expr>.metadata.create_all` is found in a remaining file (typically an app
bootstrap path such as `main.py`/`src/database.py`), that alone is a violation — a
legitimate test-only use is already excluded from the set from the start. It matches
precisely, via AST, only the `X.metadata.create_all` attribute-chain shape (both a direct
call `create_all(engine)` and passing the callable as a reference, like
`conn.run_sync(Base.metadata.create_all)`, are caught by this same attribute pattern).
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, norm, passed, read, rel, skipped


def _is_create_all_chain(node: ast.expr) -> bool:
    """Checks whether this is an Attribute chain shaped like `<expr>.metadata.create_all`."""
    if not isinstance(node, ast.Attribute) or node.attr != "create_all":
        return False
    inner = node.value
    return isinstance(inner, ast.Attribute) and inner.attr == "metadata"


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("no-orm-autosync-in-prod-config")
    found = False
    for f in py_files:
        r = rel(root, f)
        rn = norm(r)
        if rn == "tests" or rn.startswith("tests/") or "/tests/" in rn:
            continue
        src = read(f)
        if "create_all" not in src:
            continue

        found = True
        try:
            tree = ast.parse(src, filename=f)
        except SyntaxError as e:
            result.add(failed(r, f"Failed to parse the file: {e}"))
            continue

        hits = [node.lineno for node in ast.walk(tree) if _is_create_all_chain(node)]
        if hits:
            lines = ", ".join(f"line {ln}" for ln in hits)
            result.add(
                failed(
                    r,
                    f"{lines}: a Base.metadata.create_all(...) call — automatic ORM schema"
                    " sync is forbidden in an app bootstrap path; the schema must be managed"
                    " only via an Alembic migration (persistence.md). create_all is allowed"
                    " only in a test-only fixture",
                )
            )
        else:
            result.add(passed(f"{r} (no-orm-autosync-in-prod-config)"))
    if not found:
        result.add(skipped("No non-test Python file contains a create_all call"))
    return result
