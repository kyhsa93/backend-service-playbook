"""[7] shared-infra: outbox·task_queue

The outbox trigger is determined by "is there code that actually references
OutboxWriter" — this avoids being fooled by an unrelated file whose name happens to
contain the string "outbox". Be careful: in a structure where every actual file lives
inside src/outbox/, a "find a file outside it" condition could produce a false SKIP of
"no outbox pattern."

The outbox's components are OutboxWriter (loading) + OutboxPoller (publishing) +
OutboxConsumer (receiving) — the trigger symbol used is OutboxWriter.

The task_queue directory name uses an underscore — a hyphen isn't a valid Python
package/module name (`from ..task-queue.x import Y` is a SyntaxError), so it uses an
underscore for the same reason as the event-placement rule's
`application/integration_event/`.
"""

from __future__ import annotations

import os

from .common import RuleResult, failed, norm, passed, read, skipped


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("shared-infra")
    src_dir = os.path.join(root, "src")

    uses_outbox_writer = any("OutboxWriter" in read(f) for f in py_files)
    # Catches both an actual use of TaskOutboxWriter (a correctly-placed implementation)
    # and a misplaced file whose basename merely contains "task_queue" but which lives
    # outside the designated directory (reflecting the outbox's past "find a file outside
    # it" bug, both are detected from the start this time) as triggers.
    uses_task_outbox_writer = any("TaskOutboxWriter" in read(f) for f in py_files)
    has_misplaced_task_file = any(
        "task_queue" in os.path.basename(f) and "/task_queue/" not in norm(f) for f in py_files
    )
    has_task_file = uses_task_outbox_writer or has_misplaced_task_file

    if uses_outbox_writer:
        outbox_dir = os.path.join(src_dir, "outbox") if os.path.isdir(src_dir) else None
        if not outbox_dir or not os.path.isdir(outbox_dir):
            result.add(failed("src/outbox/", "References OutboxWriter, but there's no src/outbox/ directory"))
        else:
            checks = {
                "outbox_writer.py": os.path.isfile(os.path.join(outbox_dir, "outbox_writer.py")),
                "outbox_poller.py": os.path.isfile(os.path.join(outbox_dir, "outbox_poller.py")),
                "outbox_consumer.py": os.path.isfile(os.path.join(outbox_dir, "outbox_consumer.py")),
            }
            if all(checks.values()):
                result.add(
                    passed("src/outbox/ (confirmed the OutboxWriter/OutboxPoller/OutboxConsumer implementation)")
                )
            else:
                missing = [name for name, ok in checks.items() if not ok]
                result.add(
                    failed(
                        "src/outbox/",
                        "The src/outbox/ directory exists, but cannot find " + ", ".join(missing),
                    )
                )
    else:
        result.add(skipped("No outbox pattern"))

    if has_task_file:
        task_dir = os.path.join(src_dir, "task_queue") if os.path.isdir(src_dir) else None
        if not task_dir or not os.path.isdir(task_dir):
            result.add(failed("src/task_queue/", "A task file exists, but there's no src/task_queue/"))
        elif uses_task_outbox_writer:
            checks = {
                "task_outbox_writer.py": os.path.isfile(os.path.join(task_dir, "task_outbox_writer.py")),
                "task_outbox_poller.py": os.path.isfile(os.path.join(task_dir, "task_outbox_poller.py")),
                "task_consumer.py": os.path.isfile(os.path.join(task_dir, "task_consumer.py")),
            }
            if all(checks.values()):
                result.add(
                    passed(
                        "src/task_queue/ (confirmed the TaskOutboxWriter/TaskOutboxPoller/TaskConsumer implementation)"
                    )
                )
            else:
                missing = [name for name, ok in checks.items() if not ok]
                result.add(
                    failed(
                        "src/task_queue/",
                        "The src/task_queue/ directory exists, but cannot find " + ", ".join(missing),
                    )
                )
        else:
            result.add(passed("src/task_queue/"))
    else:
        result.add(skipped("No task_queue pattern"))

    return result
