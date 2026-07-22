"""[24] soft-delete-filter: whether a SQLAlchemy model with mutable state has a deleted_at
column, and a find-family query filters on deleted_at IS NULL (persistence.md)

Mechanically verifies 2 persistence.md principles:
1. "Every Entity with mutable state gets created_at/updated_at/deleted_at" — fails if a
   model has an `updated_at` column (i.e. its state changes) but no `deleted_at` column.
   An immutable record (a model with no `updated_at` at all, like `TransactionModel`) isn't
   targeted from the start — this carries over, as-is into code, the same criterion the
   document states (an immutable record is the exception).
2. "Deletion is a soft delete, a lookup always includes deleted_at IS NULL" — for a model
   with `deleted_at` per the criterion above, checks whether a `find_*` method that looks
   it up includes a filter like `<Model>.deleted_at.is_(None)`. Rather than perfectly
   tracing the exact SQLAlchemy chaining via AST (this repository's actual pattern, where
   `select(X).where(...)` gets reassigned across multiple lines), it checks as text whether
   `select(<Model>)` and `<Model>.deleted_at.is_(None)` are both present within the
   `find_*` method's source slice — a practical choice matching the composition pattern
   this repository actually uses.
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, norm, passed, read, rel, skipped


def _is_model_class(node: ast.ClassDef) -> bool:
    return any(isinstance(b, ast.Name) and b.id == "Base" for b in node.bases)


def _column_names(node: ast.ClassDef) -> set[str]:
    names = set()
    for stmt in node.body:
        if isinstance(stmt, ast.AnnAssign) and isinstance(stmt.target, ast.Name):
            names.add(stmt.target.id)
    return names


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("soft-delete-filter")
    found = False

    for f in py_files:
        fn = norm(f)
        if "/infrastructure/persistence/" not in fn:
            continue

        src = read(f)
        try:
            tree = ast.parse(src, filename=f)
        except SyntaxError as e:
            result.add(failed(rel(root, f), f"Failed to parse the file: {e}"))
            continue

        lines = src.splitlines()
        r = rel(root, f)

        model_classes = [n for n in ast.walk(tree) if isinstance(n, ast.ClassDef) and _is_model_class(n)]
        if not model_classes:
            continue
        found = True

        find_methods = [
            n
            for n in ast.walk(tree)
            if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name.startswith("find_")
        ]

        for model in model_classes:
            columns = _column_names(model)
            is_mutable = "updated_at" in columns
            has_deleted_at = "deleted_at" in columns
            label = f"{r}::{model.name}"

            if not is_mutable:
                # An immutable record (e.g. TransactionModel) — persistence.md's explicit exception, not targeted
                continue

            if not has_deleted_at:
                result.add(
                    failed(
                        label,
                        "It's an Entity with mutable state (it has an updated_at column),"
                        " but has no deleted_at column — every Entity with mutable state"
                        " must have created_at/updated_at/deleted_at (persistence.md)",
                    )
                )
                continue

            # Checks whether a find_* method referencing a model with deleted_at includes the filter
            select_pattern = f"select({model.name})"
            filter_pattern = f"{model.name}.deleted_at.is_(None)"
            referencing_methods = [
                m for m in find_methods if select_pattern in "\n".join(lines[m.lineno - 1 : (m.end_lineno or m.lineno)])
            ]

            if not referencing_methods:
                result.add(passed(f"{label} (has a deleted_at column; no find_* in this file looks it up)"))
                continue

            all_filtered = True
            for m in referencing_methods:
                method_src = "\n".join(lines[m.lineno - 1 : (m.end_lineno or m.lineno)])
                if filter_pattern not in method_src:
                    all_filtered = False
                    result.add(
                        failed(
                            f"{r}::{m.name}",
                            f"Uses select({model.name}) but has no {filter_pattern} filter —"
                            " a soft-deleted row could be included in the query result (persistence.md)",
                        )
                    )

            if all_filtered:
                result.add(passed(f"{label} (every find_* includes the deleted_at IS NULL filter)"))

    if not found:
        result.add(skipped("No SQLAlchemy model (extending Base) in infrastructure/persistence/"))
    return result
