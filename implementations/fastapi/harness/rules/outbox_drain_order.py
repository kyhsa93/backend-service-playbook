"""[11] Outbox 드레인 순서 — save() 호출 뒤에 process_pending() 호출 (domain-events.md)

OutboxRelay를 참조하는 Command Handler는 저장(save) 커밋 이후 반드시
process_pending()을 호출해 Outbox를 드레인해야 한다. 이 검사는 파일명·배치가 아니라
실제 텍스트 순서를 본다 — 이게 없으면 dual-write 회귀(process_pending 호출 삭제,
또는 알림을 직접 호출하는 것으로 되돌림)를 다른 어떤 규칙도 잡아내지 못한다.
"""

from __future__ import annotations

import re

from .common import RuleResult, failed, norm, passed, read, rel, skipped

SAVE_CALL = re.compile(r"\.save\(")
PROCESS_PENDING_CALL = re.compile(r"\.process_pending\(")


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("outbox-drain-order")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/application/command/" not in fn:
            continue
        src = read(f)
        if "OutboxRelay" not in src:
            continue
        found = True
        r = rel(root, f)
        save_match = SAVE_CALL.search(src)
        pp_match = PROCESS_PENDING_CALL.search(src)
        if not save_match:
            result.add(failed(r, "OutboxRelay를 참조하지만 save(...) 호출을 찾을 수 없음"))
        elif not pp_match:
            result.add(
                failed(
                    r,
                    "OutboxRelay를 참조하지만 process_pending() 호출이 없음"
                    " — 저장 직후 Outbox 드레인 누락(domain-events.md)",
                )
            )
        elif pp_match.start() < save_match.start():
            result.add(
                failed(r, "process_pending() 호출이 save(...) 호출보다 먼저 등장함 — 커밋 이후 드레인 순서 위반")
            )
        else:
            result.add(passed(f"{r} (save → process_pending 순서 확인)"))
    if not found:
        result.add(skipped("OutboxRelay를 사용하는 Command Handler 없음"))
    return result
