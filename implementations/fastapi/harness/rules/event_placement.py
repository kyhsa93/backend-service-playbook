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
                result.add(failed(r, "An event handler must be in application/event/"))

        if name.endswith("_integration_event.py"):
            found = True
            # The directory uses underscores (snake_case) — a hyphen isn't a valid Python
            # package/module name (`from x.integration-event import y` is a SyntaxError),
            # so it follows the same rule as this repository's other application/
            # subpackages (command/event/query).
            if "/application/integration_event/" in fn:
                result.add(passed(r))
            else:
                result.add(failed(r, "An integration event must be in application/integration_event/"))

        if name.endswith("_integration_event_controller.py"):
            found = True
            # A consumer-side adapter that receives an Integration Event published by an
            # external BC. Since it's an input boundary at the same location (interface/)
            # as an HTTP Router, it must be in interface/integration_event/.
            if "/interface/integration_event/" in fn:
                result.add(passed(r))
            else:
                result.add(failed(r, "An integration event controller must be in interface/integration_event/"))

    if not found:
        result.add(skipped("No event handler"))
    return result
