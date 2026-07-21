"""[28] no-cross-bc-domain-import: 한 Bounded Context의 domain/ 이 다른 BC의 domain/ 을 직접
import 금지 (tactical-ddd.md "다른 Aggregate는 ID 참조만 허용한다")

tactical-ddd.md의 "다른 Aggregate는 ID 참조만 허용한다(객체 참조 금지)" 원칙은 같은 BC
안의 Aggregate 사이(예: payment/Refund가 Payment 클래스를 직접 참조하지 않아야 함 —
`no-cross-aggregate-reference` 규칙이 이를 검사)뿐 아니라, 서로 다른 BC 사이에도 동일하게
적용된다. `domain-layer-isolation`(rules/domain_layer_isolation.py)은 domain/ 이
application/·infrastructure/·interface/ 레이어를 import하지 않는지만 검사하고,
`no-cross-aggregate-reference`는 `payment/domain/{payment.py,refund.py}` 안에서만
검사한다 — 이 둘 사이에 `src/card/domain/*.py`가 `src/payment/domain/*`(다른 BC의
domain 패키지)를 직접 import하는 것을 막는 규칙이 없었다. 이 규칙이 그 공백을 닫는다.

**도메인 판별**: 파일 경로에서 `domain` 세그먼트 바로 앞 세그먼트를 "이 파일이 속한 BC"로
본다(예: `src/card/domain/card.py` → `card`).

**위반 판별**: import 모듈 경로에서 `domain` 세그먼트를 찾고, 그 앞에 있는 세그먼트가
자기 자신의 BC 이름도 아니고 `src`도 아니며 공유 인프라 디렉터리(`common`/`database`/
`outbox`/`task_queue`/`config`, `common.is_shared_dir`)도 아니면 위반이다. 같은 BC
안에서의 상대 import(`from .account_status import ...`, `from .errors import ...`)는
모듈 문자열에 `domain` 세그먼트 자체가 없으므로(파이썬 상대 import는 같은 패키지 안에서는
패키지명을 반복하지 않는다) 오탐하지 않는다. `from ...common.generate_id import
generate_id`처럼 공유 유틸을 참조하는 것도 `common`이 공유 디렉터리로 취급되어 오탐하지
않는다.
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, is_shared_dir, norm, passed, read, rel, skipped


def _own_bc(fn: str) -> str | None:
    parts = fn.split("/")
    if "domain" not in parts:
        return None
    idx = parts.index("domain")
    if idx == 0:
        return None
    return parts[idx - 1]


def _module_names(node: ast.AST) -> list[str]:
    if isinstance(node, ast.ImportFrom):
        return [node.module] if node.module else []
    if isinstance(node, ast.Import):
        return [alias.name for alias in node.names]
    return []


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("no-cross-bc-domain-import")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/domain/" not in fn:
            continue
        own_bc = _own_bc(fn)
        if own_bc is None:
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
                if "domain" not in segments:
                    continue
                idx = segments.index("domain")
                prefix = segments[:idx]
                if not prefix:
                    continue  # 같은 BC 상대 import ("domain.x") — BC 이름 없음
                other_bc = prefix[-1]
                if other_bc in {"src", own_bc} or is_shared_dir(other_bc):
                    continue
                violations.append(f"{module} ({other_bc} BC)")

        if violations:
            result.add(
                failed(
                    r,
                    f"domain/({own_bc}) 이 다른 BC의 domain/ 을 직접 import함 — 다른 Aggregate는"
                    " ID 참조만 허용되고 객체 참조는 금지됨(tactical-ddd.md): " + "; ".join(violations),
                )
            )
        else:
            result.add(passed(f"{r} (no-cross-bc-domain-import)"))
    if not found:
        result.add(skipped("domain/ Python 파일 없음"))
    return result
