"""[11] Outbox 동기 드레인 금지 (domain-events.md)

Command Handler는 저장(save) 후 곧바로 반환해야 한다 — Outbox → SQS 발행/수신은
독립적으로 주기 실행되는 OutboxPoller/OutboxConsumer만의 책임이다. Command Handler가
OutboxRelay/OutboxPoller/OutboxConsumer를 직접 참조하거나 process_pending()/run_forever()류를
호출하면, Outbox 패턴이 원래 분리하려던 "쓰기"와 "이벤트 처리"가 다시 한 요청 안에
묶여버린다.
"""

from __future__ import annotations

import re

from .common import RuleResult, failed, norm, passed, read, rel, skipped

FORBIDDEN_SYMBOL = re.compile(r"\bOutboxRelay\b|\bOutboxPoller\b|\bOutboxConsumer\b")
FORBIDDEN_CALL = re.compile(r"\.\s*(?:process_pending|run_forever|poll|drain_once)\s*\(")


def _strip_comments(src: str) -> str:
    # 이 규칙이 "왜 OutboxPoller를 호출하면 안 되는지" 설명하는 주석/docstring 자체를
    # 위반으로 오탐하는 것을 막는다 — 이 저장소에서 실제로 있었던 유사 사고(issue #229류,
    # 문자열 "OutboxRelay"가 주석에 등장해 다른 규칙을 오탐시킨 사례)를 반영한 방어적
    # 처리다. `#` 줄 주석과 triple-quoted 문자열(모듈/클래스/메서드 docstring) 둘 다 제거한다
    # — Python docstring은 문법적으로 문자열 리터럴이라 `#` 제거만으로는 걸러지지 않는다.
    without_docstrings = re.sub(r'"""[\s\S]*?"""|\'\'\'[\s\S]*?\'\'\'', "", src)
    return re.sub(r"#.*$", "", without_docstrings, flags=re.MULTILINE)


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("outbox-no-sync-drain")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/application/command/" not in fn:
            continue
        found = True
        r = rel(root, f)
        src = _strip_comments(read(f))
        symbol_match = FORBIDDEN_SYMBOL.search(src)
        call_match = FORBIDDEN_CALL.search(src)
        if symbol_match or call_match:
            result.add(
                failed(
                    r,
                    "Command Handler가 OutboxRelay/OutboxPoller/OutboxConsumer를 직접 참조하거나 드레인을"
                    " 호출함 — 저장 후 곧바로 반환해야 하며, Outbox → SQS 발행/수신은 독립적으로 주기"
                    " 실행되는 OutboxPoller/OutboxConsumer만의 책임이다(동기 드레인 금지, domain-events.md)",
                )
            )
        else:
            result.add(passed(f"{r} (동기 드레인 미참조 확인)"))
    if not found:
        result.add(skipped("application/command/ 파일 없음"))
    return result
