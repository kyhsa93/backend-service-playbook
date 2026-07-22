"""[4] Handler — application/command/ or application/query/"""

from __future__ import annotations

import os

from .common import RuleResult, failed, norm, passed, rel, skipped


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("handler-placement")
    found = False
    for f in py_files:
        name = os.path.basename(f)
        if not name.endswith("_handler.py") or name.endswith("_event_handler.py"):
            continue
        found = True
        r = rel(root, f)
        fn = norm(f)
        if "/application/command/" in fn or "/application/query/" in fn:
            result.add(passed(r))
        else:
            result.add(failed(r, "A handler file must be in application/command/ or application/query/"))
    if not found:
        result.add(skipped("No handler file"))
    return result
