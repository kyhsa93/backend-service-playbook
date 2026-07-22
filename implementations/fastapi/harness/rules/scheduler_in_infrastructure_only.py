"""[19] scheduler-in-infrastructure-only: scheduling/a background loop is placed only in
infrastructure/ (scheduling.md)

APScheduler (`AsyncIOScheduler`, `@scheduler.scheduled_job`) or Celery (`@app.task`,
`@shared_task`), or a hand-written polling loop based on `asyncio.create_task` intended to
run repeatedly, must be located only in `infrastructure/` (or the top-level
`src/outbox/`, which is already treated as equivalent to infrastructure) — following
scheduling.md's principle that the Scheduler "only serves as a trigger, without directly
executing business logic." A scheduling decorator is never hung directly on `domain/` or
`application/`.

The `asyncio.create_task()` + `while True: ... await asyncio.sleep(1)` loop in
`src/outbox/outbox_poller.py` isn't an exception to this rule — it's simply out of scope
from the start, since `src/outbox/` is a shared-infrastructure directory outside the 4
domain layers, already treated as equivalent to infrastructure (shared-modules.md).
"""

from __future__ import annotations

import re

from .common import RuleResult, failed, norm, passed, read, rel, skipped

FORBIDDEN = re.compile(
    r"\bAsyncIOScheduler\b"
    r"|@\s*scheduler\.scheduled_job"
    r"|from apscheduler|import apscheduler"
    r"|from celery|import celery"
    r"|@\s*(?:\w+\.)?(?:task|shared_task)\s*\("
    r"|asyncio\.create_task"
)


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("scheduler-in-infrastructure-only")
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
                    "domain/·application/ directly uses a scheduling/background loop"
                    " (APScheduler/Celery/asyncio.create_task) — the Scheduler must be"
                    " located only in infrastructure/, and business logic must be delegated"
                    " to an Application Handler call (scheduling.md)",
                )
            )
        else:
            result.add(passed(f"{r} (scheduler-in-infrastructure-only)"))
    if not found:
        result.add(skipped("No Python file in domain/·application/"))
    return result
