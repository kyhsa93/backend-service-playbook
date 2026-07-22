"""[30] api-documentation: whether every REST endpoint has a complete, machine-readable
OpenAPI documentation entry (api-response.md "Machine-readable API documentation (OpenAPI)")

api-response.md pins down the completeness bar for "documented" as more than just "the
endpoint appears in the auto-generated /docs page" — FastAPI generates a schema entry for
every route regardless, which is exactly why this is easy to leave incomplete without
anyone noticing (a bare route with no `summary`/`description`/`responses=` still renders in
Swagger UI, just with no useful content).

This rule statically inspects every `@router.get/post/put/patch/delete(...)` call in a file
that defines an `APIRouter(...)` and fails a route if either:

1. its decorator's keyword arguments don't include both `summary=` and `description=`
   (an operation with only a route path registered, or only one of the two, isn't enough)
2. no non-2xx status code is documented in `responses={...}` — checked as the union of the
   route's own `responses=` keyword and the router-level `responses=` passed to
   `APIRouter(...)` (FastAPI merges the two for every route on that router, the same way a
   class-level `@ApiUnauthorizedResponse` in nestjs covers every method in the controller)

This intentionally does not care WHICH non-2xx status is documented, or how many — the
point is that at least one failure path is captured; cross-checking that failure path
against the handler's *actual* error-mapping is a job for a human reviewer (or a future,
narrower rule), not this structural check.
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, passed, read, rel, skipped

HTTP_METHODS = {"get", "post", "put", "patch", "delete"}


def _route_call(dec: ast.expr) -> tuple[str, str] | None:
    """Returns (method, path) if `dec` is `<name>.<method>(...)` for an HTTP method name —
    e.g. `router.post("/accounts/{account_id}/deposit", ...)`."""
    if not isinstance(dec, ast.Call):
        return None
    func = dec.func
    if not (isinstance(func, ast.Attribute) and func.attr in HTTP_METHODS):
        return None
    path = ""
    if dec.args and isinstance(dec.args[0], ast.Constant) and isinstance(dec.args[0].value, str):
        path = dec.args[0].value
    return func.attr, path


def _dict_int_keys(node: ast.expr | None) -> set[int]:
    if not isinstance(node, ast.Dict):
        return set()
    keys: set[int] = set()
    for k in node.keys:
        if isinstance(k, ast.Constant) and isinstance(k.value, int):
            keys.add(k.value)
    return keys


def _has_non_2xx(keys: set[int]) -> bool:
    return any(not (200 <= status < 300) for status in keys)


def _router_level_response_keys(tree: ast.Module) -> set[int]:
    for node in ast.walk(tree):
        if not isinstance(node, ast.Assign) or not isinstance(node.value, ast.Call):
            continue
        call = node.value
        if not (isinstance(call.func, ast.Name) and call.func.id == "APIRouter"):
            continue
        for kw in call.keywords:
            if kw.arg == "responses":
                return _dict_int_keys(kw.value)
    return set()


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("api-documentation")

    router_files = [f for f in py_files if "APIRouter(" in read(f)]
    found_any = False

    for f in router_files:
        src = read(f)
        try:
            tree = ast.parse(src, filename=f)
        except SyntaxError:
            continue
        r = rel(root, f)
        router_keys = _router_level_response_keys(tree)
        router_has_non_2xx = _has_non_2xx(router_keys)

        for node in ast.walk(tree):
            if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                continue

            route_info = None
            route_dec = None
            for dec in node.decorator_list:
                info = _route_call(dec)
                if info is not None:
                    route_info = info
                    route_dec = dec
                    break
            if route_info is None or route_dec is None:
                continue

            found_any = True
            method, path = route_info
            label = f"{r}::{method.upper()} {path or '/'} ({node.name})"

            kw_names = {kw.arg for kw in route_dec.keywords}
            has_summary = "summary" in kw_names
            has_description = "description" in kw_names
            if not has_summary or not has_description:
                missing = [
                    name
                    for name, present in (("summary", has_summary), ("description", has_description))
                    if not present
                ]
                result.add(
                    failed(
                        label,
                        f"Missing {'/'.join(missing)} on the route decorator — a route with no summary/"
                        "description isn't sufficient documentation (api-response.md)",
                    )
                )
            else:
                result.add(passed(f"{label} (summary/description present)"))

            route_keys: set[int] = set()
            for kw in route_dec.keywords:
                if kw.arg == "responses":
                    route_keys = _dict_int_keys(kw.value)
            if router_has_non_2xx or _has_non_2xx(route_keys):
                result.add(passed(f"{label} (a non-2xx response is documented)"))
            else:
                result.add(
                    failed(
                        label,
                        "Only the success response is documented — no non-2xx status appears in "
                        "responses={...} (route-level or router-level) (api-response.md)",
                    )
                )

    if not found_any:
        result.add(skipped("No APIRouter route (@router.get/post/put/patch/delete) found"))
    return result
