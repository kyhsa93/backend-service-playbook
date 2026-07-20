"""[27] query-handler-no-raw-aggregate: Query Handler의 execute()가 도메인 Aggregate를 그대로
반환하면 안 됨, 전용 Result 타입을 반환해야 함 (api-response.md "Result 객체")

api-response.md는 Query Handler(`GetAccountHandler` 등)가 도메인 `Account` 객체를 그대로
반환하지 않고 `application/query/result.py`의 전용 Result dataclass로 변환해 반환해야
한다고 못박는다 — Aggregate는 비즈니스 로직·내부 이벤트 버퍼(`_events`)를 포함해 직렬화하면
내부 구현이 노출된다.

특정 도메인 이름(Account/Card/Payment/Refund)을 하드코딩하지 않는다 — harness는 특정
업무 도메인 지식을 전제로 삼지 않는다(harness.md). 대신 구조로 판별한다: `execute()`의
반환 타입 주석에 쓰인 이름이 **`domain` 세그먼트를 포함한 모듈에서 import된 이름**이면
"raw Aggregate 반환"으로 본다 — 이 저장소의 관례상 Result 타입은 항상 같은 패키지의
`.result`(또는 `result`로 끝나는 상대 모듈)에서 import되고, Aggregate/Enum 등은
`domain/` 하위 모듈에서 import되기 때문이다. `list[X]`, `X | None` 등으로 감싸도 내부
이름을 추출해 동일하게 검사한다.
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
            result.add(failed(r, f"파일을 파싱할 수 없음: {e}"))
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
                    "Query Handler의 execute()가 domain/ 에서 import한 타입을 그대로 반환함 —"
                    " application/query/result.py의 전용 Result 타입으로 변환해 반환해야 함"
                    f"(api-response.md): {'; '.join(violations)}",
                )
            )
        else:
            result.add(passed(f"{r} (query-handler-no-raw-aggregate)"))
    if not found:
        result.add(skipped("application/query/ Handler 없음"))
    return result
