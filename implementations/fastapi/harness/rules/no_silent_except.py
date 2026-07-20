"""[20] no-silent-except: application/·infrastructure/ 에서 예외를 조용히 삼키는 `except: pass` 금지
(observability.md)

observability.md "에러는 반드시 로깅 후 전파"·"삼키지 않는다" 원칙 — `except ...: pass`(핸들러
본문이 정확히 `pass` 하나뿐)는 예외를 로깅도, 재전파도 하지 않고 조용히 버리는 대표적인
안티패턴이다. AST 기반으로 핸들러 본문이 정확히 `[Pass]`인 경우만 잡는다 — 로깅 호출이나
주석이 있으면(주석은 AST에 남지 않지만, `logger.exception(...)` 등 실제 문(statement)이
하나라도 있으면) 잡히지 않는다. `domain/`은 애초에 예외 처리보다 상태 전이 검증(도메인
에러 raise)만 하므로 대상에서 제외한다.
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, norm, passed, read, rel, skipped


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("no-silent-except")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/application/" not in fn and "/infrastructure/" not in fn:
            continue
        found = True
        src = read(f)
        r = rel(root, f)

        try:
            tree = ast.parse(src, filename=f)
        except SyntaxError as e:
            result.add(failed(r, f"파일을 파싱할 수 없음: {e}"))
            continue

        violation_lines = [
            node.lineno
            for node in ast.walk(tree)
            if isinstance(node, ast.ExceptHandler) and len(node.body) == 1 and isinstance(node.body[0], ast.Pass)
        ]

        if violation_lines:
            lines = ", ".join(str(n) for n in violation_lines)
            result.add(
                failed(
                    r,
                    f"line {lines}: except ...: pass — 예외를 로깅 없이 조용히 삼킴. 로깅 후 전파하거나"
                    " (재시도 대상이면) Outbox 등 별도 신뢰성 메커니즘으로 보완해야 함(observability.md)",
                )
            )
        else:
            result.add(passed(f"{r} (no-silent-except)"))
    if not found:
        result.add(skipped("application/·infrastructure/ Python 파일 없음"))
    return result
