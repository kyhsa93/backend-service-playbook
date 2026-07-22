"""[11] Synchronously draining the Outbox is forbidden (domain-events.md)

A Command Handler must return immediately after saving — publishing/receiving Outbox →
SQS is the sole responsibility of the independently, periodically running
OutboxPoller/OutboxConsumer. If a Command Handler references
OutboxRelay/OutboxPoller/OutboxConsumer directly, or calls something like
process_pending()/run_forever(), then "writing" and "event processing," which the Outbox
pattern was meant to separate, get bundled back into a single request.
"""

from __future__ import annotations

import re

from .common import RuleResult, failed, norm, passed, read, rel, skipped

FORBIDDEN_SYMBOL = re.compile(r"\bOutboxRelay\b|\bOutboxPoller\b|\bOutboxConsumer\b")
FORBIDDEN_CALL = re.compile(r"\.\s*(?:process_pending|run_forever|poll|drain_once)\s*\(")


def _strip_comments(src: str) -> str:
    # Guards against a false-positive violation just because a comment/docstring that
    # explains "why OutboxPoller must not be called" itself mentions a string like
    # "OutboxRelay". Removes both `#` line comments and triple-quoted strings
    # (module/class/method docstrings) — a Python docstring is syntactically a string
    # literal, so removing only `#` wouldn't filter it out.
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
                    "The Command Handler directly references OutboxRelay/OutboxPoller/OutboxConsumer"
                    " or calls a drain method — it must return immediately after saving, and"
                    " publishing/receiving Outbox → SQS is the sole responsibility of the"
                    " independently, periodically running OutboxPoller/OutboxConsumer"
                    " (synchronous draining is forbidden, domain-events.md)",
                )
            )
        else:
            result.add(passed(f"{r} (confirmed no synchronous drain reference)"))
    if not found:
        result.add(skipped("No file in application/command/"))
    return result
