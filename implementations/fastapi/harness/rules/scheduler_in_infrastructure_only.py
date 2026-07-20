"""[19] scheduler-in-infrastructure-only: 스케줄링/백그라운드 루프는 infrastructure/에만 배치
(scheduling.md)

APScheduler(`AsyncIOScheduler`, `@scheduler.scheduled_job`)나 Celery(`@app.task`, `@shared_task`),
또는 반복 실행을 의도한 `asyncio.create_task` 기반 손으로 짠 폴링 루프는 Scheduler가
"비즈니스 로직을 직접 실행하지 않고 트리거 역할만 한다"는 scheduling.md 원칙에 따라
`infrastructure/`(또는 이미 infrastructure에 준하는 최상위 `src/outbox/`)에만 위치해야 한다
— `domain/`, `application/`에 스케줄링 데코레이터를 직접 걸지 않는다.

`src/outbox/outbox_poller.py`의 `asyncio.create_task()` + `while True: ... await
asyncio.sleep(1)` 루프는 이 규칙의 예외가 아니라 애초에 대상 밖이다 — `src/outbox/`는
도메인 4레이어 밖의 공유 인프라 디렉토리로 이미 infrastructure에 준하는 위치로 취급한다
(shared-modules.md).
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
                    "domain/·application/ 에 스케줄링/백그라운드 루프(APScheduler/Celery/"
                    "asyncio.create_task)를 직접 사용함 — Scheduler는 infrastructure/에만 위치해야"
                    " 하며, 비즈니스 로직은 Application Handler 호출로 위임해야 함(scheduling.md)",
                )
            )
        else:
            result.add(passed(f"{r} (scheduler-in-infrastructure-only)"))
    if not found:
        result.add(skipped("domain/·application/ Python 파일 없음"))
    return result
