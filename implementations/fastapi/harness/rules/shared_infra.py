"""[7] shared-infra: outbox·task_queue

outbox 트리거는 "OutboxWriter를 실제로 참조하는 코드가 있는가"로 판단한다 — 파일명에
우연히 "outbox" 문자열이 들어간 무관한 파일에 낚이지 않기 위함이다(과거 버그: 실제
파일들이 이미 전부 src/outbox/ 안에 있어서 "밖에 있는 파일 찾기" 조건이 항상 거짓이
되어 "outbox 패턴 없음"이라는 거짓 SKIP을 내고 있었다).

2026-07 async 전환으로 outbox 구성 요소가 OutboxWriter(적재)+OutboxPoller(발행)+
OutboxConsumer(수신)로 바뀌었다 — 예전에는 OutboxWriter+OutboxRelay(적재+동기 드레인)
2개였다. 트리거 심볼도 OutboxRelay(더 이상 존재하지 않는 클래스)에서 OutboxWriter로
옮겼다.

task_queue 디렉토리명은 underscore를 쓴다 — 하이픈은 유효한 Python 패키지/모듈 이름이
아니라서(`from ..task-queue.x import Y`는 SyntaxError) event-placement 규칙의
`application/integration_event/`와 동일한 이유로 underscore를 쓴다(이전에는 "task-queue"
하이픈 표기였다 — Python에서는 애초에 만들 수 없는 디렉토리라 실제로 어떤 구현체도
이 표기를 따를 수 없었으므로 바로잡았다).
"""

from __future__ import annotations

import os

from .common import RuleResult, failed, norm, passed, read, skipped


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("shared-infra")
    src_dir = os.path.join(root, "src")

    uses_outbox_writer = any("OutboxWriter" in read(f) for f in py_files)
    # TaskOutboxWriter 실제 사용(정상적으로 배치된 구현)과, "task_queue"라는 이름만 basename에
    # 들어갔지만 지정 디렉토리 밖에 있는 오배치 파일(과거 outbox의 "밖에 있는 파일 찾기" 버그를
    # 반영해 이번엔 처음부터 둘 다 감지) 둘 다 트리거로 잡는다.
    uses_task_outbox_writer = any("TaskOutboxWriter" in read(f) for f in py_files)
    has_misplaced_task_file = any(
        "task_queue" in os.path.basename(f) and "/task_queue/" not in norm(f) for f in py_files
    )
    has_task_file = uses_task_outbox_writer or has_misplaced_task_file

    if uses_outbox_writer:
        outbox_dir = os.path.join(src_dir, "outbox") if os.path.isdir(src_dir) else None
        if not outbox_dir or not os.path.isdir(outbox_dir):
            result.add(failed("src/outbox/", "OutboxWriter를 참조하지만 src/outbox/ 디렉토리가 없음"))
        else:
            checks = {
                "outbox_writer.py": os.path.isfile(os.path.join(outbox_dir, "outbox_writer.py")),
                "outbox_poller.py": os.path.isfile(os.path.join(outbox_dir, "outbox_poller.py")),
                "outbox_consumer.py": os.path.isfile(os.path.join(outbox_dir, "outbox_consumer.py")),
            }
            if all(checks.values()):
                result.add(passed("src/outbox/ (OutboxWriter/OutboxPoller/OutboxConsumer 구현 확인)"))
            else:
                missing = [name for name, ok in checks.items() if not ok]
                result.add(
                    failed(
                        "src/outbox/",
                        "src/outbox/ 디렉토리는 있으나 " + ", ".join(missing) + "를 찾을 수 없음",
                    )
                )
    else:
        result.add(skipped("outbox 패턴 없음"))

    if has_task_file:
        task_dir = os.path.join(src_dir, "task_queue") if os.path.isdir(src_dir) else None
        if not task_dir or not os.path.isdir(task_dir):
            result.add(failed("src/task_queue/", "task 파일이 있으나 src/task_queue/ 없음"))
        elif uses_task_outbox_writer:
            checks = {
                "task_outbox_writer.py": os.path.isfile(os.path.join(task_dir, "task_outbox_writer.py")),
                "task_outbox_poller.py": os.path.isfile(os.path.join(task_dir, "task_outbox_poller.py")),
                "task_consumer.py": os.path.isfile(os.path.join(task_dir, "task_consumer.py")),
            }
            if all(checks.values()):
                result.add(passed("src/task_queue/ (TaskOutboxWriter/TaskOutboxPoller/TaskConsumer 구현 확인)"))
            else:
                missing = [name for name, ok in checks.items() if not ok]
                result.add(
                    failed(
                        "src/task_queue/",
                        "src/task_queue/ 디렉토리는 있으나 " + ", ".join(missing) + "를 찾을 수 없음",
                    )
                )
        else:
            result.add(passed("src/task_queue/"))
    else:
        result.add(skipped("task_queue 패턴 없음"))

    return result
