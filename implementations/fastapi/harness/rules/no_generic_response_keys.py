"""[26] no-generic-response-keys: 목록 응답 Pydantic 모델의 리스트 필드명이 범용 키(result/data/items)면
안 됨, 도메인 객체명 복수형이어야 함 (api-response.md)

api-response.md "목록 조회 응답 형식"은 목록 응답의 키 이름을 **도메인 객체명 복수형**
(`transactions`, `accounts`, `payments`)으로 못박고, `result`/`data`/`items` 같은 범용
키는 금지한다 — 실제 예시: `GetTransactionsResponse.transactions`, `GetPaymentsResponse.payments`.

`interface/rest/`의 Pydantic `BaseModel` 서브클래스 중 `list[...]`/`List[...]` 타입
필드만 대상으로 한다(단건 응답 필드는 이 규칙의 대상이 아니다 — 목록 응답 형식 규칙이므로).
필드명이 정확히 `result`/`data`/`items`(대소문자 무관)면 위반이다.
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
            result.add(failed(r, f"파일을 파싱할 수 없음: {e}"))
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
                    "목록 응답 필드명이 범용 키(result/data/items)임 — 도메인 객체명 복수형을"
                    f" 사용해야 함(api-response.md): {', '.join(violations)}",
                )
            )
        else:
            result.add(passed(f"{r} (no-generic-response-keys)"))
    if not found:
        result.add(skipped("interface/rest/ 목록 응답 Pydantic 모델 없음"))
    return result
