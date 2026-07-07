"""[8] event-placement"""
from __future__ import annotations

import os

from .common import RuleResult, failed, norm, passed, rel, skipped


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("event-placement")
    found = False
    for f in py_files:
        name = os.path.basename(f)
        fn = norm(f)
        r = rel(root, f)

        if name.endswith("_event_handler.py"):
            found = True
            if "/application/event/" in fn:
                result.add(passed(r))
            else:
                result.add(failed(r, "이벤트 핸들러는 application/event/ 에 있어야 함"))

        if name.endswith("_integration_event.py"):
            found = True
            if "/application/integration-event/" in fn:
                result.add(passed(r))
            else:
                result.add(failed(r, "integration event는 application/integration-event/ 에 있어야 함"))

    if not found:
        result.add(skipped("이벤트 핸들러 없음"))
    return result
