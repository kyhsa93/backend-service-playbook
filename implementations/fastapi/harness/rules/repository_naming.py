"""[13] Repository/Query method naming (repository-pattern.md)

A Repository/Query ABC interface in the domain/ layer must unify lookups into a single
`find_<noun>s` (a single-item lookup also reuses the same method via `take=1`), saves into
a single `save_<noun>`, and deletes into a single `delete_<noun>`. A concrete
implementation in `infrastructure/persistence/` isn't targeted — it's free to have
private/internal helper methods (repository-impl is itself a separate rule that checks
placement).

A blocklist approach (narrow and precise) — only an abstract method matching one of the
anti-patterns below is caught as a failure. Instead of a broad positive grammar (which
would false-positive on a legitimate method like `has_transaction_with_reference`), only
known violation patterns are matched:
- A `find_by_*` shape (the name contains "find_by") — e.g. find_by_id, find_by_account_id_and_owner_id
- Exactly `find_all` (a bare lookup with no noun)
- A method starting with `count` — a count must be included in the (list, count) tuple
  `find_<noun>s` returns, not a separate method
- Exactly `save` (bare, no noun suffix) — passes once a noun is attached, like `save_account`
- Exactly `delete` (bare, no noun suffix) — passes once a noun is attached, like `delete_account`
- A method starting with `update_` — a separate update method is never added. A state
  change is expressed by loading the Aggregate via `find_<noun>s`, mutating it via a domain
  method, then saving it whole again via `save_<noun>` (a separate method for a partial
  update is forbidden)
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, norm, passed, read, rel, skipped

DOC_REF = "docs/architecture/repository-pattern.md"


def _violation_reason(name: str) -> str | None:
    if "find_by" in name:
        return (
            f"'{name}' — find_by_* is forbidden; a single-item lookup must also be unified via"
            " find_<noun>s(..., take=1)"
        )
    if name == "find_all":
        return "'find_all' — a bare lookup with no noun is forbidden; express it as find_<noun>s"
    if name.startswith("count"):
        return (
            f"'{name}' — a count-only method is forbidden; it must be included in"
            " find_<noun>s's (list, count) tuple return"
        )
    if name == "save":
        return "'save' — a bare save is forbidden; specify a noun via save_<noun>"
    if name == "delete":
        return "'delete' — a bare delete is forbidden; specify a noun via delete_<noun>"
    if name.startswith("update_"):
        return (
            f"'{name}' — a separate update_* method is forbidden; load via find, mutate via a"
            " domain method, then save via save_<noun>"
        )
    return None


def _is_abstractmethod(node: ast.FunctionDef | ast.AsyncFunctionDef) -> bool:
    for d in node.decorator_list:
        if isinstance(d, ast.Name) and d.id == "abstractmethod":
            return True
        if isinstance(d, ast.Attribute) and d.attr == "abstractmethod":
            return True
    return False


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("repository-naming")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/domain/" not in fn:
            continue
        src = read(f)
        if "abstractmethod" not in src:
            continue

        r = rel(root, f)
        try:
            tree = ast.parse(src, filename=f)
        except SyntaxError as e:
            found = True
            result.add(failed(r, f"Failed to parse the file: {e}"))
            continue

        checked_any = False
        violations: list[str] = []
        for node in ast.walk(tree):
            if not isinstance(node, ast.ClassDef):
                continue
            if not (node.name.endswith("Repository") or node.name.endswith("Query")):
                continue
            for item in node.body:
                if not isinstance(item, ast.FunctionDef | ast.AsyncFunctionDef):
                    continue
                if not _is_abstractmethod(item):
                    continue
                checked_any = True
                reason = _violation_reason(item.name)
                if reason:
                    violations.append(f"{node.name}.{item.name}: {reason}")

        if not checked_any:
            continue
        found = True
        if violations:
            result.add(
                failed(
                    r,
                    "A Repository/Query abstract method name violates the naming rule — "
                    f"it must follow the find_<noun>s/save_<noun>/delete_<noun> pattern ({DOC_REF}): "
                    + "; ".join(violations),
                )
            )
        else:
            result.add(passed(f"{r} (repository-naming)"))
    if not found:
        result.add(skipped("No Repository·Query ABC in domain/"))
    return result
