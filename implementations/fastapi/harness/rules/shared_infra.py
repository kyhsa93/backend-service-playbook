"""[7] shared-infra: outbox·task-queue

outbox 트리거는 "OutboxRelay를 실제로 참조하는 코드가 있는가"로 판단한다 — 파일명에
우연히 "outbox" 문자열이 들어간 무관한 파일에 낚이지 않기 위함이다(과거 버그: 실제
파일들이 이미 전부 src/outbox/ 안에 있어서 "밖에 있는 파일 찾기" 조건이 항상 거짓이
되어 "outbox 패턴 없음"이라는 거짓 SKIP을 내고 있었다).
"""

from __future__ import annotations

import os

from .common import RuleResult, failed, norm, passed, read, skipped


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("shared-infra")
    src_dir = os.path.join(root, "src")

    uses_outbox_relay = any("OutboxRelay" in read(f) for f in py_files)
    has_task_file = any("task_queue" in os.path.basename(f) and "/task-queue/" not in norm(f) for f in py_files)

    if uses_outbox_relay:
        outbox_dir = os.path.join(src_dir, "outbox") if os.path.isdir(src_dir) else None
        if not outbox_dir or not os.path.isdir(outbox_dir):
            result.add(failed("src/outbox/", "OutboxRelay를 참조하지만 src/outbox/ 디렉토리가 없음"))
        else:
            has_writer = os.path.isfile(os.path.join(outbox_dir, "outbox_writer.py"))
            has_relay = os.path.isfile(os.path.join(outbox_dir, "outbox_relay.py"))
            if has_writer and has_relay:
                result.add(passed("src/outbox/ (OutboxWriter/OutboxRelay 구현 확인)"))
            else:
                missing = [n for n, ok in (("outbox_writer.py", has_writer), ("outbox_relay.py", has_relay)) if not ok]
                result.add(
                    failed(
                        "src/outbox/",
                        "src/outbox/ 디렉토리는 있으나 " + ", ".join(missing) + "를 찾을 수 없음",
                    )
                )
    else:
        result.add(skipped("outbox 패턴 없음"))

    if has_task_file:
        task_dir = os.path.join(src_dir, "task-queue") if os.path.isdir(src_dir) else None
        if task_dir and os.path.isdir(task_dir):
            result.add(passed("src/task-queue/"))
        else:
            result.add(failed("src/task-queue/", "task 파일이 있으나 src/task-queue/ 없음"))
    else:
        result.add(skipped("task-queue 패턴 없음"))

    return result
