"""[17] no-direct-env-access-outside-config: domain/·application/ 에서 os.environ/os.getenv 직접 호출 금지
(config.md)

환경 변수 접근은 `config/`의 Pydantic `BaseSettings` 클래스(`DatabaseConfig`, `JwtConfig`,
`AwsConfig`)로 캡슐화하고, `infrastructure/`(및 `config/` 자신)에서만 그 설정 클래스나
`os.environ`/`os.getenv`를 직접 참조한다 — `domain/`, `application/`은 설정에 직접
의존하지 않는다(config.md "설정 접근 패턴" 절).

정규식 기반 블록리스트: `os.environ`, `os.getenv(` 리터럴 사용만 잡는다 — `getenv`/`environ`을
가리키는 지역 변수 별칭까지는 추적하지 않는다(이 저장소 전체에 그런 간접 별칭 사용 사례가
없고, 과도한 일반화는 오히려 오탐 위험을 늘린다).
"""

from __future__ import annotations

import re

from .common import RuleResult, failed, norm, passed, read, rel, skipped

FORBIDDEN = re.compile(r"\bos\.environ\b|\bos\.getenv\s*\(")


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("no-direct-env-access-outside-config")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/domain/" not in fn and "/application/" not in fn:
            continue
        found = True
        src = read(f)
        r = rel(root, f)
        if FORBIDDEN.search(src):
            result.add(
                failed(
                    r,
                    "domain/·application/ 은 os.environ/os.getenv를 직접 호출할 수 없음 — config/의"
                    " BaseSettings 클래스로 캡슐화해야 함(config.md)",
                )
            )
        else:
            result.add(passed(f"{r} (no-direct-env-access-outside-config)"))
    if not found:
        result.add(skipped("domain/·application/ Python 파일 없음"))
    return result
