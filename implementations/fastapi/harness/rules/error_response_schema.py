"""[23] error-response-schema: whether the global exception handlers' response body has
exactly 4 fields (error-handling.md)

error-handling.md pins down that every error response must have exactly 4 fields —
`statusCode` (number)/`code` (string)/`message` (string|array)/`error` (string) — and no
others. It traces the response body (`JSONResponse(..., content=...)`) built by a handler
registered via `@app.exception_handler(...)`, and checks whether that body's field set
matches these 4 exactly (no more, no fewer, no different names).

Two patterns are supported for how the body is built:
1. A dict literal written directly in `content=` — its keys are compared as-is
2. Calling a separate function, like `content=builder_func(...)` — its function definition
   is located and its return expression is traced recursively. If the return expression is
   shaped like `SomeModel(...).model_dump()`/`.dict()`, the `SomeModel` (a Pydantic
   `BaseModel` subclass) definition is located and its class body's field annotations
   (`AnnAssign`) are used as the field set — this is accurate because the fields actually
   serialized are the model's own field definitions, not the keywords the caller passed.
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, passed, read, rel, skipped

REQUIRED_FIELDS = {"statusCode", "code", "message", "error"}
MAX_HOPS = 4


def _is_exception_handler_decorator(dec: ast.expr) -> bool:
    # The shape @app.exception_handler(SomeError) — Call(func=Attribute(attr="exception_handler"))
    if not isinstance(dec, ast.Call):
        return False
    func = dec.func
    return isinstance(func, ast.Attribute) and func.attr == "exception_handler"


def _dict_keys(node: ast.Dict) -> set[str] | None:
    keys: set[str] = set()
    for k in node.keys:
        if isinstance(k, ast.Constant) and isinstance(k.value, str):
            keys.add(k.value)
        else:
            return None  # a dynamic key — can't be determined statically
    return keys


def _class_field_names(tree: ast.Module, class_name: str) -> set[str] | None:
    for node in ast.walk(tree):
        if isinstance(node, ast.ClassDef) and node.name == class_name:
            fields = set()
            for stmt in node.body:
                if isinstance(stmt, ast.AnnAssign) and isinstance(stmt.target, ast.Name):
                    fields.add(stmt.target.id)
            return fields
    return None


def _find_func_def(all_trees: dict[str, ast.Module], func_name: str) -> ast.FunctionDef | ast.AsyncFunctionDef | None:
    for tree in all_trees.values():
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == func_name:
                return node
    return None


def _find_class_owner_tree(all_trees: dict[str, ast.Module], class_name: str) -> ast.Module | None:
    for tree in all_trees.values():
        for node in ast.walk(tree):
            if isinstance(node, ast.ClassDef) and node.name == class_name:
                return tree
    return None


def _resolve_return_value_expr(func_node: ast.FunctionDef | ast.AsyncFunctionDef) -> ast.expr | None:
    for stmt in ast.walk(func_node):
        if isinstance(stmt, ast.Return) and stmt.value is not None:
            return stmt.value
    return None


def _resolve_field_set(expr: ast.expr | None, all_trees: dict[str, ast.Module], hops: int = 0) -> set[str] | None:
    if expr is None or hops > MAX_HOPS:
        return None

    if isinstance(expr, ast.Dict):
        return _dict_keys(expr)

    if isinstance(expr, ast.Call):
        func = expr.func
        # SomeModel(...).model_dump() / .dict()
        if isinstance(func, ast.Attribute) and func.attr in {"model_dump", "dict"} and isinstance(func.value, ast.Call):
            inner = func.value
            if isinstance(inner.func, ast.Name):
                return _class_field_names_anywhere(inner.func.id, all_trees)
            return None
        # builder_func(...)
        if isinstance(func, ast.Name):
            target = _find_func_def(all_trees, func.id)
            if target is None:
                return None
            return _resolve_field_set(_resolve_return_value_expr(target), all_trees, hops + 1)

    return None


def _class_field_names_anywhere(class_name: str, all_trees: dict[str, ast.Module]) -> set[str] | None:
    tree = _find_class_owner_tree(all_trees, class_name)
    if tree is None:
        return None
    return _class_field_names(tree, class_name)


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("error-response-schema")

    all_trees: dict[str, ast.Module] = {}
    for f in py_files:
        src = read(f)
        try:
            all_trees[f] = ast.parse(src, filename=f)
        except SyntaxError:
            continue

    handler_files = [f for f in py_files if f in all_trees and "exception_handler(" in read(f)]

    found_any = False
    for f in handler_files:
        tree = all_trees[f]
        r = rel(root, f)
        for node in ast.walk(tree):
            if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                continue
            if not any(_is_exception_handler_decorator(d) for d in node.decorator_list):
                continue
            found_any = True
            label = f"{r}::{node.name}"

            content_expr: ast.expr | None = None
            for sub in ast.walk(node):
                if isinstance(sub, ast.Call):
                    for kw in sub.keywords:
                        if kw.arg == "content":
                            content_expr = kw.value
                            break
                if content_expr is not None:
                    break

            if content_expr is None:
                result.add(
                    failed(label, "Cannot find the response body (content=) — expected JSONResponse(content=...)")
                )
                continue

            fields = _resolve_field_set(content_expr, all_trees)
            if fields is None:
                result.add(
                    failed(
                        label,
                        "Cannot statically trace the response body's field composition —"
                        " it must be a dict literal or a builder function returning a"
                        " Pydantic model (.model_dump()/.dict())",
                    )
                )
                continue

            if fields == REQUIRED_FIELDS:
                result.add(passed(f"{label} (matches the 4 fields statusCode/code/message/error)"))
            else:
                missing = REQUIRED_FIELDS - fields
                extra = fields - REQUIRED_FIELDS
                detail = []
                if missing:
                    detail.append(f"missing: {sorted(missing)}")
                if extra:
                    detail.append(f"unnecessary/differently named: {sorted(extra)}")
                result.add(
                    failed(
                        label,
                        f"The error response's fields don't exactly match the 4 fields"
                        f" statusCode/code/message/error — {', '.join(detail)} (error-handling.md)",
                    )
                )

    if not found_any:
        result.add(skipped("No @app.exception_handler(...) registration"))
    return result
