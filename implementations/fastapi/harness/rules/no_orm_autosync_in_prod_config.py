"""[29] no-orm-autosync-in-prod-config: 앱 부트스트랩 경로에서 `Base.metadata.create_all(...)`
호출 금지 (persistence.md "마이그레이션 — Alembic으로 관리")

persistence.md는 `create_all`이 테이블이 없을 때만 생성하고 기존 테이블의 컬럼 추가/변경/
삭제를 감지하지 못하므로 프로덕션 스키마 변경에는 쓸 수 없다고 못박는다 — 스키마는
Alembic 마이그레이션(`alembic upgrade head`)으로만 관리해야 한다. `create_all`은 로컬
개발·테스트 환경(매 실행마다 새 DB를 만드는 testcontainers fixture 등)에서만 허용된다.

`harness/rules/common.py`의 `collect_py_files`가 이미 `test_*.py`/`conftest.py`/
`migrations/`(Alembic 전용 디렉터리)를 이 규칙이 받는 `py_files` 목록에서 제외하므로,
남아있는 파일(대표적으로 `main.py`/`src/database.py` 같은 앱 부트스트랩 경로)에서
`<expr>.metadata.create_all`이 발견되면 그 자체로 위반이다 — 정상적인 테스트 전용 사용은
애초에 집합에서 제외되어 있다. AST로 `X.metadata.create_all` 형태의 attribute chain만
정밀하게 매칭한다(직접 호출 `create_all(engine)`과, `conn.run_sync(Base.metadata.create_all)`
처럼 콜러블을 참조로 넘기는 형태 모두 이 attribute 패턴으로 잡힌다).
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, norm, passed, read, rel, skipped


def _is_create_all_chain(node: ast.expr) -> bool:
    """`<expr>.metadata.create_all` 형태의 Attribute 체인인지 확인한다."""
    if not isinstance(node, ast.Attribute) or node.attr != "create_all":
        return False
    inner = node.value
    return isinstance(inner, ast.Attribute) and inner.attr == "metadata"


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("no-orm-autosync-in-prod-config")
    found = False
    for f in py_files:
        r = rel(root, f)
        rn = norm(r)
        if rn == "tests" or rn.startswith("tests/") or "/tests/" in rn:
            continue
        src = read(f)
        if "create_all" not in src:
            continue

        found = True
        try:
            tree = ast.parse(src, filename=f)
        except SyntaxError as e:
            result.add(failed(r, f"파일을 파싱할 수 없음: {e}"))
            continue

        hits = [node.lineno for node in ast.walk(tree) if _is_create_all_chain(node)]
        if hits:
            lines = ", ".join(f"line {ln}" for ln in hits)
            result.add(
                failed(
                    r,
                    f"{lines}: Base.metadata.create_all(...) 호출 — 앱 부트스트랩 경로에서 ORM 자동"
                    " 스키마 동기화 금지, 스키마는 Alembic 마이그레이션으로만 관리해야 함"
                    "(persistence.md). create_all은 테스트 전용 fixture에서만 허용됨",
                )
            )
        else:
            result.add(passed(f"{r} (no-orm-autosync-in-prod-config)"))
    if not found:
        result.add(skipped("create_all 호출을 포함한 비-테스트 Python 파일 없음"))
    return result
