"""[26] no-generic-response-keys: a list-query response Pydantic model's list field name
must never be a generic key (result/data/items) — it must be the plural of the domain
object name (api-response.md)

api-response.md's "List-query response shape" pins the list-response key name down to
**the plural of the domain object name** (`transactions`, `accounts`, `payments`), and
forbids a generic key such as `result`/`data`/`items` — actual examples:
`GetTransactionsResponse.transactions`, `GetPaymentsResponse.payments`.

Only fields of type `list[...]`/`List[...]` on a Pydantic `BaseModel` subclass in
`interface/rest/` are targeted (a single-item response field isn't targeted by this rule —
since this is a list-response-shape rule). It's a violation if the field name is exactly
`result`/`data`/`items` (case-insensitive).
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, norm, passed, read, rel, skipped

GENERIC_KEYS = {"result", "data", "items"}


def _is_base_model(cls: ast.ClassDef) -> bool:
    for base in cls.bases:
        if isinstance(base, ast.Name) and base.id == "BaseModel":
            return True
        if isinstance(base, ast.Attribute) and base.attr == "BaseModel":
            return True
    return False


def _is_list_type(ann: ast.expr | None) -> bool:
    if ann is None:
        return False
    if isinstance(ann, ast.Subscript):
        value = ann.value
        if isinstance(value, ast.Name) and value.id in {"list", "List"}:
            return True
        if isinstance(value, ast.Attribute) and value.attr in {"list", "List"}:
            return True
    return False


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("no-generic-response-keys")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/interface/rest/" not in fn:
            continue
        src = read(f)
        if "BaseModel" not in src:
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
            if not isinstance(node, ast.ClassDef) or not _is_base_model(node):
                continue
            for item in node.body:
                if not isinstance(item, ast.AnnAssign) or not isinstance(item.target, ast.Name):
                    continue
                if not _is_list_type(item.annotation):
                    continue
                checked_any = True
                if item.target.id.lower() in GENERIC_KEYS:
                    violations.append(f"{node.name}.{item.target.id}")

        if not checked_any:
            continue
        found = True
        if violations:
            result.add(
                failed(
                    r,
                    "The list-response field name is a generic key (result/data/items) — the"
                    f" plural of the domain object name must be used (api-response.md): {', '.join(violations)}",
                )
            )
        else:
            result.add(passed(f"{r} (no-generic-response-keys)"))
    if not found:
        result.add(skipped("No list-response Pydantic model in interface/rest/"))
    return result
