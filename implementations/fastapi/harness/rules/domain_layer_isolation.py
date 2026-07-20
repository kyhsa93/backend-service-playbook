"""[14] domain-layer-isolation: domain/ 레이어의 레이어·도메인 간 import 금지 (layer-architecture.md)

domain-purity(rules/domain_purity.py)가 프레임워크/라이브러리 이름(fastapi/sqlalchemy/aioboto3)
블록리스트로 domain/의 순수성을 검사하는 것과 달리, 이 규칙은 **경로 기반 구조 검사**다 —
domain/ 파일은 (같은 도메인이든 다른 도메인이든) 어떤 application/, infrastructure/,
interface/ 패키지도 import할 수 없다. Domain 레이어는 어느 레이어에도 의존하지 않는다는
layer-architecture.md의 의존 방향 원칙을 AST로 강제한다.

블록리스트 방식: import 대상 모듈 경로의 세그먼트(점으로 분리한 각 조각)에 "application",
"infrastructure", "interface"가 정확히 일치하는 경우만 위반으로 잡는다 — 상대 import
(`from ..application.x import Y` → module="application.x")와 절대/크로스도메인 import
(`from ...card.infrastructure.x import Y` → module="card.infrastructure.x") 모두 이 방식으로
동일하게 잡힌다. 우연히 이름에 "interface" 등이 포함된 무관한 서드파티 모듈(예: 없음, 이
저장소 의존성 목록엔 해당 없음)과의 오탐을 피하려 정확한 세그먼트 일치만 사용한다.
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, norm, passed, read, rel, skipped

FORBIDDEN_SEGMENTS = {"application", "infrastructure", "interface"}


def _module_names(node: ast.AST) -> list[str]:
    if isinstance(node, ast.ImportFrom):
        return [node.module] if node.module else []
    if isinstance(node, ast.Import):
        return [alias.name for alias in node.names]
    return []


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("domain-layer-isolation")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/domain/" not in fn:
            continue
        found = True
        r = rel(root, f)
        src = read(f)

        try:
            tree = ast.parse(src, filename=f)
        except SyntaxError as e:
            result.add(failed(r, f"파일을 파싱할 수 없음: {e}"))
            continue

        violations: list[str] = []
        for node in ast.walk(tree):
            if not isinstance(node, (ast.ImportFrom, ast.Import)):
                continue
            for module in _module_names(node):
                segments = module.split(".")
                hit = FORBIDDEN_SEGMENTS.intersection(segments)
                if hit:
                    violations.append(f"{module} ({'/'.join(sorted(hit))})")

        if violations:
            result.add(
                failed(
                    r,
                    "domain/ 은 application/·infrastructure/·interface/ (같은 도메인·다른 도메인 모두)를"
                    " import할 수 없음 — domain 레이어는 어떤 레이어에도 의존하지 않아야 함"
                    "(layer-architecture.md): " + "; ".join(violations),
                )
            )
        else:
            result.add(passed(f"{r} (domain-layer-isolation)"))
    if not found:
        result.add(skipped("domain/ Python 파일 없음"))
    return result
