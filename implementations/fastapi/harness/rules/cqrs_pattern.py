"""[12] CQRS — application/query/ 는 쓰기용 Repository를 참조하지 않아야 함 (cqrs-pattern.md)

QueryHandler는 읽기 전용 Query 인터페이스(예: AccountQuery)에만 의존해야 한다. 쓰기용
Repository(예: AccountRepository, save() 포함)를 그대로 주입받으면 컴파일/타입 상으로도
쓰기 메서드에 접근 가능해져 CQRS 분리가 무의미해진다 — nestjs harness의
evaluators/rules/cqrs-pattern.evaluator.ts와 동일한 취지를 이식한 규칙이다.
"""

from __future__ import annotations

from .common import RuleResult, failed, norm, passed, read, rel, skipped


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("cqrs-pattern")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/application/query/" not in fn:
            continue
        found = True
        r = rel(root, f)
        src = read(f)
        if "Repository" in src:
            result.add(
                failed(
                    r,
                    "application/query/ 는 쓰기용 Repository를 참조하지 않아야 함"
                    " — 읽기 전용 Query 인터페이스에만 의존(cqrs-pattern.md)",
                )
            )
        else:
            result.add(passed(f"{r} (Repository 미참조 확인)"))
    if not found:
        result.add(skipped("application/query/ 파일 없음"))
    return result
