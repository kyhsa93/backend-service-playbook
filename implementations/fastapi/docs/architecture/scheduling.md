# 스케줄링 / 배치 작업

> 프레임워크 무관 원칙: [../../../../docs/architecture/scheduling.md](../../../../docs/architecture/scheduling.md)

이 저장소에는 아직 (Outbox 이외의) 진짜 Cron형 배치 작업이 없다. 이 문서는 필요해질 때(예: 만료 계좌 정리 배치 등)를 위한 FastAPI/Python 패턴을 정의한다.

**주의**: [domain-events.md](domain-events.md)의 `src/outbox/outbox_poller.py`(`OutboxPoller.run_forever()`)가 실제로 1초 주기의 백그라운드 폴링 루프다 — 다만 아래에서 다루는 APScheduler가 아니라, `main.py`의 `lifespan`이 기동하는 `asyncio.create_task()` + `while True: ... await asyncio.sleep(1)` 형태의 손으로 짠(hand-rolled) 루프다. Outbox → SQS 발행이라는 단일하고 고정된 책임만 있어 APScheduler의 Cron 표현식·잡 관리 기능이 필요 없었기 때문이다. 아래 절은 이 Outbox 폴링과 무관하게, 여러 종류의 주기적 배치(만료 계좌 정리 등)를 관리해야 할 때 참고할 일반적인 FastAPI 패턴이다.

---

## 선택지 — `BackgroundTasks` vs APScheduler vs Celery

| | FastAPI `BackgroundTasks` | APScheduler | Celery |
|---|---|---|---|
| 실행 시점 | 응답 반환 직후, 같은 프로세스 | Cron/interval, 같은 프로세스 | 별도 워커 프로세스, 메시지 브로커 경유 |
| 다중 인스턴스 안전성 | 없음 (인스턴스 수만큼 중복 실행) | 없음 (별도 잠금 없이는 중복 실행) | 있음 (브로커가 큐 역할) |
| 실패 시 재시도 | 없음 — 예외는 로그에만 남고 소실 | 없음 (기본) | 있음 (`retry`, `max_retries`) |
| 적합한 작업 | 응답 후 즉시, 실패해도 무방한 짧은 후처리 (예: 로그 flush) | 단일 인스턴스 배포 또는 멱등 처리 전제하의 주기 작업 | 여러 인스턴스, 안정적 재시도가 필요한 배치 |
| 이 저장소에 추가할 의존성 | 없음 (FastAPI 내장) | `apscheduler` | `celery` + Redis/RabbitMQ |

**권장:** 이 프로젝트 규모(단일 Bounded Context, 단순 배치)에서는 **APScheduler**를 기본으로 한다. Cron 스케줄이 필요하고, `BackgroundTasks`보다 더 명시적인 스케줄 관리를 제공하면서도 Celery급 인프라(브로커, 워커 클러스터)가 필요 없기 때문이다. 여러 인스턴스로 수평 확장하면서 정확히 한 번(또는 안전한 재시도)이 보장되어야 하는 배치(대규모 정산 등)가 생기면 Celery + 메시지 큐로 전환을 검토한다 — root의 Task Queue/Outbox 패턴이 이 전환의 설계 기반이 된다.

`BackgroundTasks`는 스케줄링 도구가 아니라 "응답 후 후처리" 도구다 — 예를 들어 [domain-events.md](domain-events.md)에서 다루는 알림 발송을 지금의 `OutboxPoller`/`OutboxConsumer` 경로 대신 `BackgroundTasks`로 옮기면, 응답 전송 이후 프로세스가 죽었을 때 유실 문제가 그대로 남는다(재시도 메커니즘이 없으므로 — Outbox 테이블도, SQS의 visibility timeout+DLQ도 없다). 재시도가 필요한 작업에는 적합하지 않다.

---

## Scheduler는 Infrastructure 레이어에 배치

Scheduler는 **비즈니스 로직을 직접 실행하지 않는다**. 필요하면 Application Handler를 호출하는 얇은 트리거 역할만 한다.

아래는 (Outbox용 `OutboxPoller`/`OutboxConsumer`와는 별개로) Cron형 배치가 생길 때의 가상 예시다 — 예를 들어 만료 계좌를 주기적으로 정리하는 `cleanup_expired_accounts()`라는 Application Handler가 있다고 하자:

```python
# infrastructure/scheduling/expired_account_scheduler.py (가상 예시 — 신설 제안)
import logging

from apscheduler.schedulers.asyncio import AsyncIOScheduler

from ...application.command.cleanup_expired_accounts_handler import CleanupExpiredAccountsHandler

logger = logging.getLogger(__name__)


def start_expired_account_scheduler(session_factory) -> AsyncIOScheduler:
    scheduler = AsyncIOScheduler()

    @scheduler.scheduled_job("interval", seconds=5, id="cleanup-expired-accounts")
    async def _run() -> None:
        try:
            async with session_factory() as session:
                await CleanupExpiredAccountsHandler(session).execute()
        except Exception:
            logger.exception("expired_account_scheduler_failed")
            # 예외를 재throw하지 않는다 — 다음 tick에서 재시도

    scheduler.start()
    return scheduler
```

```python
# main.py — lifespan에서 시작/종료
from src.account.infrastructure.scheduling.expired_account_scheduler import start_expired_account_scheduler


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncGenerator[None, None]:
    ...
    scheduler = start_expired_account_scheduler(SessionLocal)
    yield
    scheduler.shutdown(wait=True)   # 진행 중인 job이 끝날 때까지 대기 후 종료
```

**Cron 예외를 반드시 명시적으로 로깅한다.** APScheduler는 job 안에서 발생한 예외를 자동으로 삼키고 다음 tick에서 계속 실행하지만, `try-except` + `logger.exception()` 없이는 실패가 관찰 불가능해진다.

`domain/`·`application/`에 APScheduler/Celery/`asyncio.create_task` 기반 스케줄링이 직접 등장하는지는 harness의 `scheduler-in-infrastructure-only` 규칙이 검사한다 — `src/outbox/outbox_poller.py`의 `asyncio.create_task()` 루프는 `src/outbox/`가 이미 infrastructure에 준하는 공유 디렉토리([shared-modules.md](shared-modules.md))라 대상 밖이다.

---

## 멱등성 — at-least-once 전제

APScheduler는 단일 프로세스 안에서 동작하므로 메시지 큐 같은 at-least-once 전달 보장은 없지만, 여러 uvicorn 워커/컨테이너 인스턴스를 띄우면 **같은 Cron이 인스턴스 수만큼 동시에 실행**된다는 문제는 여전히 남는다. `relay_pending_events()`처럼 "아직 처리되지 않은 row만 골라 처리하고, 처리 완료 여부를 DB에 기록하는" 패턴 자체가 본질적으로 멱등하다면 여러 인스턴스가 동시에 실행돼도 안전하다 (row 단위 UPDATE의 원자성에 의존).

여러 인스턴스가 겹쳐 실행되면 안 되는 배치(예: 일 1회 정산)라면, DB row 잠금(`SELECT ... FOR UPDATE SKIP LOCKED`) 또는 분산 락(`advisory lock`)으로 단일 실행을 보장해야 한다 — 이는 APScheduler 자체가 제공하지 않는 기능이므로 명시적으로 구현해야 한다.

---

## 대용량/장시간 배치 — Celery로 전환할 신호

다음 신호가 나타나면 APScheduler에서 Celery + 메시지 큐로 전환을 검토한다.

- 배치 실행 시간이 다음 스케줄 주기를 초과하기 시작한다.
- 실패한 배치를 자동으로 재시도해야 하고, 재시도 횟수/백오프 정책이 필요하다.
- 여러 인스턴스 중 정확히 하나만 실행되어야 하는 요구가 늘어난다.
- 배치 실행 상태(성공/실패/재시도 횟수)를 대시보드로 관찰해야 한다.

---

## 원칙

- **Scheduler는 Infrastructure 레이어**: Application/Domain에 스케줄링 데코레이터를 직접 걸지 않는다.
- **Scheduler는 트리거만**: 비즈니스 로직은 Application Handler에 위임한다.
- **`BackgroundTasks`는 재시도가 필요 없는 후처리 전용**: 신뢰성이 필요한 작업(알림, 정산)에는 쓰지 않는다.
- **Cron 예외는 명시적으로 로깅**: 스케줄링 라이브러리가 예외를 삼키는 경우가 많다.
- **작업이 커지면 Celery로 전환**: APScheduler는 단일 프로세스 규모에 적합하다.

---

### 관련 문서

- [domain-events.md](domain-events.md) — `OutboxPoller`/`OutboxConsumer`의 실제 구현(1초 주기 폴링 + SQS long polling, APScheduler 아님)
- [graceful-shutdown.md](graceful-shutdown.md) — 스케줄러의 안전한 종료(`scheduler.shutdown(wait=True)`)
- [persistence.md](persistence.md) — 배치 작업의 트랜잭션 경계
