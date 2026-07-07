"""[9] layer-dependency: application/ → infrastructure/ import 금지

의존성 역전 — application은 domain/의 추상 인터페이스(ABC/Technical Service 인터페이스)에만
의존해야 하며, infrastructure/의 구체 구현체를 직접 import하면 안 된다.
docs/architecture/domain-service.md의 Technical Service 패턴 및 layer-architecture.md의
의존 방향 규칙 참조.
"""
from __future__ import annotations

import ast

from .common import RuleResult, failed, norm, passed, read, rel, skipped


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("layer-dependency")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/application/" not in fn:
            continue
        found = True
        r = rel(root, f)
        src = read(f)

        try:
            tree = ast.parse(src, filename=f)
        except SyntaxError as e:
            result.add(failed(r, f"파일을 파싱할 수 없음: {e}"))
            continue

        violations = []
        for node in ast.walk(tree):
            if not isinstance(node, ast.ImportFrom):
                continue
            module = node.module or ""
            if "infrastructure" in module.split("."):
                names = ", ".join(alias.name for alias in node.names)
                violations.append(f"{'.' * node.level}{module} 에서 {names} import")

        if violations:
            result.add(failed(
                r,
                "application/ 은 infrastructure/ 구현체를 직접 import 할 수 없음 "
                "(의존성 역전 위반, domain/ 인터페이스에 의존해야 함): " + "; ".join(violations),
            ))
        else:
            result.add(passed(f"{r} (layer-dependency)"))

    if not found:
        result.add(skipped("application/ Python 파일 없음"))
    return result
