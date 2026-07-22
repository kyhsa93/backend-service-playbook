"""[27] query-handler-no-raw-aggregate: a Query Handler's execute() must never return the
domain Aggregate as-is — it must return a dedicated Result type (api-response.md "The
Result object")

api-response.md pins down that a Query Handler (`GetAccountHandler`, etc.) must not
return the domain `Account` object as-is, and must convert it into a dedicated Result
dataclass from `application/query/result.py` before returning — an Aggregate includes
business logic and an internal event buffer (`_events`), so serializing it exposes
internal implementation.

It never hardcodes a specific domain name (Account/Card/Payment/Refund) — the harness
never presumes specific business-domain knowledge (harness.md). Instead it determines this
structurally: if the name used in `execute()`'s return-type annotation is **a name
imported from a module containing the `domain` segment**, it's treated as "returning a raw
Aggregate" — by this repository's convention, a Result type is always imported from the
same package's `.result` (or a relative module ending in `result`), while an
Aggregate/Enum, etc. is imported from a submodule of `domain/`. Even when wrapped in
`list[X]`, `X | None`, etc., the inner name is extracted and checked the same way.
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, norm, passed, read, rel, skipped


def _imported_from_domain(tree: ast.AST) -> set[str]:
    names: set[str] = set()
    for node in ast.walk(tree):
        if not isinstance(node, ast.ImportFrom) or not node.module:
            continue
        segments = node.module.split(".")
        if "domain" not in segments:
            continue
        for alias in node.names:
            names.add(alias.asname or alias.name)
    return names


def _extract_names(node: ast.expr | None) -> list[str]:
    if node is None:
        return []
    if isinstance(node, ast.Name):
        return [node.id]
    if isinstance(node, ast.Attribute):
        return [node.attr]
    if isinstance(node, ast.Subscript):
        return _extract_names(node.slice)
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.BitOr):
        return _extract_names(node.left) + _extract_names(node.right)
    if isinstance(node, ast.Tuple):
        result: list[str] = []
        for elt in node.elts:
            result.extend(_extract_names(elt))
        return result
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        try:
            parsed = ast.parse(node.value, mode="eval").body
        except SyntaxError:
            return []
        return _extract_names(parsed)
    return []


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("query-handler-no-raw-aggregate")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/application/query/" not in fn:
            continue
        src = read(f)
        if "class" not in src or "Handler" not in src:
            continue

        r = rel(root, f)
        try:
            tree = ast.parse(src, filename=f)
        except SyntaxError as e:
            found = True
            result.add(failed(r, f"Failed to parse the file: {e}"))
            continue

        domain_names = _imported_from_domain(tree)
        if not domain_names:
            continue

        checked_any = False
        violations: list[str] = []
        for node in ast.walk(tree):
            if not isinstance(node, ast.ClassDef) or not node.name.endswith("Handler"):
                continue
            for item in node.body:
                if not isinstance(item, ast.FunctionDef | ast.AsyncFunctionDef) or item.name != "execute":
                    continue
                checked_any = True
                returned = set(_extract_names(item.returns))
                raw = returned & domain_names
                if raw:
                    violations.append(f"{node.name}.execute() -> {', '.join(sorted(raw))}")

        if not checked_any:
            continue
        found = True
        if violations:
            result.add(
                failed(
                    r,
                    "The Query Handler's execute() returns a type imported from domain/ as-is —"
                    " it must be converted into a dedicated Result type from"
                    f" application/query/result.py before returning (api-response.md): {'; '.join(violations)}",
                )
            )
        else:
            result.add(passed(f"{r} (query-handler-no-raw-aggregate)"))
    if not found:
        result.add(skipped("No Handler in application/query/"))
    return result
