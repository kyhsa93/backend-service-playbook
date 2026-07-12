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
            # 디렉토리는 underscore(snake_case)를 쓴다 — 하이픈은 유효한 Python 패키지/모듈
            # 이름이 아니라서(`from x.integration-event import y`는 SyntaxError) 이 저장소의
            # 다른 application/ 하위 패키지(command/event/query)와 동일한 규칙을 따른다.
            if "/application/integration_event/" in fn:
                result.add(passed(r))
            else:
                result.add(failed(r, "integration event는 application/integration_event/ 에 있어야 함"))

    if not found:
        result.add(skipped("이벤트 핸들러 없음"))
    return result
