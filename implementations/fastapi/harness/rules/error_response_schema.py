"""[23] error-response-schema: 전역 예외 핸들러의 응답 바디가 정확히 4개 필드인지
(error-handling.md)

error-handling.md는 모든 에러 응답이 정확히 `statusCode`(number)/`code`(string)/
`message`(string|array)/`error`(string) 네 필드만 가져야 한다고 못박는다. `@app.exception_handler(...)`
로 등록된 핸들러가 만드는 응답 바디(`JSONResponse(..., content=...)`)를 추적해, 그 바디의
필드 집합이 이 네 개와 정확히 일치하는지(더 많지도 적지도, 이름이 다르지도 않게) 검사한다.

바디 구성은 두 패턴을 지원한다:
1. `content=` 에 dict 리터럴을 직접 쓰는 경우 — 그 dict의 키를 그대로 비교
2. `content=builder_func(...)` 처럼 별도 함수를 호출하는 경우 — 그 함수 정의를 찾아가 반환식을
   재귀적으로 추적한다. 반환식이 `SomeModel(...).model_dump()`/`.dict()` 형태면 `SomeModel`
   (Pydantic `BaseModel` 서브클래스) 정의를 찾아 클래스 바디의 필드 애노테이션(`AnnAssign`)을
   필드 집합으로 사용한다 — 실제로 직렬화되는 필드는 호출자가 넘긴 키워드가 아니라 모델
   자체의 필드 정의이므로 이쪽이 정확하다.
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, passed, read, rel, skipped

REQUIRED_FIELDS = {"statusCode", "code", "message", "error"}
MAX_HOPS = 4


def _is_exception_handler_decorator(dec: ast.expr) -> bool:
    # @app.exception_handler(SomeError) 형태 — Call(func=Attribute(attr="exception_handler"))
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
            return None  # 동적 키 — 정적으로 판단 불가
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
                result.add(failed(label, "응답 바디(content=)를 찾을 수 없음 — JSONResponse(content=...) 형태여야 함"))
                continue

            fields = _resolve_field_set(content_expr, all_trees)
            if fields is None:
                result.add(
                    failed(
                        label,
                        "응답 바디의 필드 구성을 정적으로 추적할 수 없음 — dict 리터럴이거나"
                        " Pydantic 모델(.model_dump()/.dict())을 반환하는 빌더 함수여야 함",
                    )
                )
                continue

            if fields == REQUIRED_FIELDS:
                result.add(passed(f"{label} (statusCode/code/message/error 4개 필드 일치)"))
            else:
                missing = REQUIRED_FIELDS - fields
                extra = fields - REQUIRED_FIELDS
                detail = []
                if missing:
                    detail.append(f"누락: {sorted(missing)}")
                if extra:
                    detail.append(f"불필요/이름 다름: {sorted(extra)}")
                result.add(
                    failed(
                        label,
                        f"에러 응답 필드가 statusCode/code/message/error 4개와 정확히 일치하지 않음"
                        f" — {', '.join(detail)} (error-handling.md)",
                    )
                )

    if not found_any:
        result.add(skipped("@app.exception_handler(...) 등록이 없음"))
    return result
