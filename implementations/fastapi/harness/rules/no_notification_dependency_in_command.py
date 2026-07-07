"""[10] Command Handler는 Outbox 경유만 허용 — NotificationService 직접 의존 금지 (domain-events.md)

layer-dependency(rules/layer_dependency.py)는 import 경로만 보므로
application/service/의 NotificationService(ABC)를 Command Handler가 직접 의존하는
dual-write 회귀를 잡지 못한다(ABC는 infrastructure/가 아니라서 그 규칙을 통과한다) —
별도로 검사한다.
"""
from __future__ import annotations

import re

from .common import RuleResult, failed, norm, passed, read, rel, skipped

NOTIFICATION_DEP = re.compile(r'notification_service\s*:|:\s*NotificationService\b')


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("no-notification-dependency-in-command")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/application/command/" not in fn:
            continue
        found = True
        r = rel(root, f)
        src = read(f)
        if NOTIFICATION_DEP.search(src):
            result.add(failed(r, "Command Handler는 NotificationService(ABC 포함)를 직접 의존하지 않아야 함 — Outbox 경유(domain-events.md)"))
        else:
            result.add(passed(f"{r} (Outbox 경유 확인)"))
    if not found:
        result.add(skipped("Command Handler 없음"))
    return result
