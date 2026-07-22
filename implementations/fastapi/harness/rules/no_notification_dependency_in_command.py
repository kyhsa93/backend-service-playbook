"""[10] A Command Handler is only allowed to go through the Outbox — depending on
NotificationService directly is forbidden (domain-events.md)

Since layer-dependency (rules/layer_dependency.py) only looks at import paths, it can't
catch a dual-write regression where a Command Handler depends directly on
NotificationService (ABC) in application/service/ (the ABC isn't in infrastructure/, so it
passes that rule) — this is checked separately.
"""

from __future__ import annotations

import re

from .common import RuleResult, failed, norm, passed, read, rel, skipped

NOTIFICATION_DEP = re.compile(r"notification_service\s*:|:\s*NotificationService\b")


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
            result.add(
                failed(
                    r,
                    "A Command Handler must not depend on NotificationService (including"
                    " its ABC) directly — it must go through the Outbox (domain-events.md)",
                )
            )
        else:
            result.add(passed(f"{r} (confirmed it goes through the Outbox)"))
    if not found:
        result.add(skipped("No Command Handler"))
    return result
