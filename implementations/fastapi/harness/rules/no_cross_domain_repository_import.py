"""[18] no-cross-bc-repository-in-application: application/ 이 다른 도메인의 Repository/Query ABC를
직접 import 금지 (cross-domain.md, root cross-domain-communication.md)

한 도메인의 `application/`은 다른 도메인의 `domain/repository.py`(또는
`payment_repository.py`/`refund_repository.py` 등 도메인별 Repository 파일)를 직접 import할
수 없다 — 크로스 도메인 조회는 반드시 호출하는 쪽의 `application/adapter/`에 정의한 Adapter
ABC(+ `infrastructure/`의 구현체, 대상 도메인의 Query를 그 안에서 호출)를 거쳐야 한다
(cross-domain.md의 ACL 패턴, 실제 사례: `card/infrastructure/account_adapter_impl.py`,
`payment/infrastructure/{account,card}_adapter_impl.py`). 같은 도메인 안에서
`domain/repository.py`를 import하는 것은 정상이다.

**도메인 판별**: 파일 경로에서 `application` 세그먼트 바로 앞 세그먼트를 "이 파일이 속한
도메인"으로 본다 (예: `src/payment/application/command/x.py` → `payment`).

**위반 판별**: import 모듈 경로에서 `domain` 세그먼트를 찾고, 그 앞에 다른 도메인 이름
세그먼트가 있으며(상대 import는 도메인 경계를 넘을 때만 모듈 문자열에 도메인 이름이
등장한다 — 같은 도메인 상대 import는 `domain.repository`처럼 도메인 이름 없이 시작한다),
`domain` 다음 세그먼트 중 하나라도 "repository"를 포함하면(파일명 또는 import된 이름) 위반으로
잡는다. `application/adapter/`에서 자신의 Adapter ABC를 정의하는 것은 이 패턴에 걸리지 않는다
— Adapter ABC는 다른 도메인의 `domain.repository`를 import하지 않고 독자적인 뷰 타입을
정의하기 때문이다.
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, norm, passed, read, rel, skipped


def _current_domain(fn: str) -> str | None:
    parts = fn.split("/")
    if "application" not in parts:
        return None
    idx = parts.index("application")
    if idx == 0:
        return None
    return parts[idx - 1]


def _module_names(node: ast.AST) -> list[str]:
    if isinstance(node, ast.ImportFrom):
        return [node.module] if node.module else []
    if isinstance(node, ast.Import):
        return [alias.name for alias in node.names]
    return []


def _imported_names(node: ast.AST) -> list[str]:
    if isinstance(node, ast.ImportFrom):
        return [alias.name for alias in node.names]
    return []


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("no-cross-bc-repository-in-application")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/application/" not in fn:
            continue
        own_domain = _current_domain(fn)
        if own_domain is None:
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
                    continue  # 같은 도메인 상대 import ("domain.repository") — 도메인 이름 없음
                other_domain = prefix[-1]
                if other_domain in {"src", own_domain}:
                    continue
                suffix = segments[idx + 1 :]
                names = _imported_names(node)
                is_repository = any("repository" in s.lower() for s in suffix) or any(
                    "repository" in n.lower() or n.endswith("Query") for n in names
                )
                if is_repository:
                    violations.append(f"{module} ({other_domain} 도메인)")

        if violations:
            result.add(
                failed(
                    r,
                    f"application/({own_domain}) 이 다른 도메인의 Repository/Query ABC를 직접 import함 —"
                    " application/adapter/의 Adapter 인터페이스를 거쳐야 함(cross-domain.md): " + "; ".join(violations),
                )
            )
        else:
            result.add(passed(f"{r} (no-cross-bc-repository-in-application)"))
    if not found:
        result.add(skipped("application/ Python 파일 없음"))
    return result
