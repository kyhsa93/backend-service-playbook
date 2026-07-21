# 스케줄링 / Task Queue 구현 패턴

> 프레임워크 무관 원칙: [../../../../docs/architecture/scheduling.md](../../../../docs/architecture/scheduling.md)

> **이 저장소의 실제 경로**: root가 규정하는 `[Scheduler] --(enqueue)--> [task_outbox] --(Poller)--> [SQS FIFO] --(Consumer)--> [TaskController] --(호출)--> [CommandService]` 경로를 그대로 구현한다. Scheduler(APScheduler `@scheduled_job`)는 `task_outbox` row를 insert하는 것 말고는 아무것도 하지 않는다 — 이자 계산·통계 집계 같은 실제 비즈니스 로직은 `TaskConsumer`가 SQS에서 수신해 호출하는 `TaskController`(→ Command Handler)의 몫이다. `src/task_queue/`가 실제 코드이며, `task_outbox_writer.py`/`task_outbox_poller.py`/`task_consumer.py`/`task_handlers.py`로 구성된다. 여기 기술한 전체 구조는 이 저장소가 실제로 채택한 **한 가지 구현 예시**다 — 더 단순한 배치라면 `BackgroundTasks`나 단일 `@scheduled_job` + 직접 Handler 호출만으로도 충분할 수 있다(아래 "선택지" 참고).

주기적 작업 두 가지(계좌 정기 이자 지급, 카드 월간 사용내역 발송)가 이 패턴으로 구현되어 있다 — `src/account/infrastructure/scheduling/interest_scheduler.py`와 `src/card/infrastructure/scheduling/statement_scheduler.py`.

---

## 선택지 — `BackgroundTasks` vs APScheduler(단독) vs APScheduler+Task Queue

| | FastAPI `BackgroundTasks` | APScheduler 단독 (Handler 직접 호출) | APScheduler + Task Outbox + SQS FIFO (이 저장소가 채택) |
|---|---|---|---|
| 실행 시점 | 응답 반환 직후, 같은 프로세스 | Cron/interval, 같은 프로세스 | Cron은 enqueue만, 실행은 별도 Consumer |
| 다중 인스턴스 안전성 | 없음 | 없음 (별도 잠금 없이는 중복 실행) | 있음 (FIFO 큐의 `MessageDeduplicationId`) |
| 실패 시 재시도 | 없음 | 없음(기본) | 있음 (SQS visibility timeout + `maxReceiveCount` → DLQ) |
| 적합한 작업 | 응답 후 즉시, 실패해도 무방한 짧은 후처리 | 단일 인스턴스 배포, 멱등 처리 전제하의 가벼운 주기 작업 | 여러 인스턴스, 신뢰성 있는 재시도·DLQ 관찰이 필요한 배치 |
| 이 저장소에 추가된 의존성 | 없음(FastAPI 내장) | `apscheduler` | `apscheduler` + SQS FIFO 큐(도메인 이벤트와 별도) |

이자 지급·통계 발송 둘 다 "여러 인스턴스가 동시에 떠 있어도 하루/한 달에 정확히 한 번" + "실패 시 DLQ로 격리"가 필요한 배치라 세 번째 열을 택했다. `apply_daily_interest_handler.py`/`send_monthly_card_statement_handler.py`처럼 페이지 단위로 전체 Aggregate를 순회하는 배치가 늘어나면 실행 시간이 길어질 수 있는데, SQS FIFO의 visibility timeout을 넉넉히 잡는 것만으로 대응 가능한 규모다 — 그 이상(수십만 건, 분산 락 필요)이 되면 root 문서의 "선택지" 논의처럼 Celery 전환을 검토한다.

---

## Scheduler — Infrastructure 레이어, enqueue만

`src/account/infrastructure/scheduling/interest_scheduler.py`와 `src/card/infrastructure/scheduling/statement_scheduler.py`는 APScheduler의 `AsyncIOScheduler`를 감싼 팩토리 함수다. Cron 핸들러 본문은 `TaskOutboxWriter.enqueue()` 호출과 `session.commit()`뿐이다 — 이자 계산이나 카드 조회 같은 실제 로직은 전혀 없다.

```python
# src/account/infrastructure/scheduling/interest_scheduler.py — 실제 코드
TASK_TYPE = "account.interest.apply"
GROUP_ID = "account.interest"

def start_interest_scheduler(session_factory: async_sessionmaker[AsyncSession]) -> AsyncIOScheduler:
    scheduler = AsyncIOScheduler()

    @scheduler.scheduled_job("cron", hour=0, minute=0, id="apply-daily-interest")
    async def _run() -> None:
        try:
            today = date.today()
            async with session_factory() as session:
                await TaskOutboxWriter(session).enqueue(
                    task_type=TASK_TYPE,
                    payload={},
                    group_id=GROUP_ID,
                    deduplication_id=f"{TASK_TYPE}-{today.isoformat()}",
                )
                await session.commit()
        except Exception:  # noqa: BLE001
            logger.exception("정기 이자 지급 Task enqueue 실패")
            # 재throw하지 않는다 — 다음 tick(다음날)에서 재시도된다.

    scheduler.start()
    return scheduler
```

`main.py`의 `lifespan`이 앱 기동 시 `start_interest_scheduler(SessionLocal)`/`start_statement_scheduler(SessionLocal)`을 호출해 각 스케줄러를 시작하고, 종료 시 `scheduler.shutdown(wait=True)`로 진행 중인 job이 끝날 때까지 기다린 뒤 종료한다(graceful-shutdown.md 참고). `domain/`·`application/`에 이런 스케줄링 데코레이터가 직접 등장하면 harness의 `scheduler-in-infrastructure-only` 규칙이 잡아낸다 — `src/outbox/outbox_poller.py`의 `asyncio.create_task()` 루프처럼 `src/outbox/`·`src/task_queue/`는 이미 공유 인프라 위치([shared-modules.md](shared-modules.md))라 이 규칙의 대상 밖이다.

---

## Task Outbox 패턴 — 실제 구현

Cron tick에는 자연스러운 DB 트랜잭션이 없으므로, `TaskOutboxWriter.enqueue()`의 단일 row insert 자체가 원자성을 만든다. Domain Event의 `OutboxModel`/`OutboxWriter`(`src/outbox/`, [domain-events.md](domain-events.md) 참고)와 테이블·큐가 분리되어 있다는 점만 다르고 구조는 동일하다 — "쓰기와 같은 트랜잭션 안에서 적재 → 별도 Poller가 발행".

```python
# src/task_queue/task_outbox_model.py — 실제 코드
class TaskOutboxModel(Base):
    __tablename__ = "task_outbox"

    task_id: Mapped[str] = mapped_column(CHAR(32), primary_key=True)
    task_type: Mapped[str]
    payload: Mapped[str]
    group_id: Mapped[str]            # FIFO MessageGroupId
    deduplication_id: Mapped[str]    # FIFO MessageDeduplicationId(날짜/월 기반)
    processed: Mapped[bool] = mapped_column(default=False)
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
```

```python
# src/task_queue/task_outbox_writer.py — 실제 코드
class TaskOutboxWriter:
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def enqueue(self, task_type: str, payload: dict, group_id: str, deduplication_id: str) -> None:
        self._session.add(TaskOutboxModel(
            task_id=uuid.uuid4().hex,
            task_type=task_type,
            payload=json.dumps(payload, default=str),
            group_id=group_id,
            deduplication_id=deduplication_id,
            processed=False,
        ))
```

`TaskOutboxPoller`(`src/task_queue/task_outbox_poller.py`)는 `OutboxPoller`와 완전히 동일한 책임 분리로 동작한다 — 1초 주기로 `processed=False` 행을 최대 100개씩 읽어 `SqsConfig().task_queue_url`(FIFO 큐)로 발행하고, `MessageGroupId`/`MessageDeduplicationId`를 함께 보낸다. 발행에 성공한 행만 `processed=True`로 표시한다. `main.py`의 `lifespan`이 `TaskOutboxPoller(SessionLocal)`을 `asyncio.create_task()`로 앱 기동 시 한 번만 띄운다 — Scheduler(Cron 핸들러)는 이 클래스를 참조하지 않는다.

---

## Task Controller — Interface 레이어

`src/account/interface/task/account_task_controller.py`와 `src/card/interface/task/card_task_controller.py`가 Task Controller다. HTTP Router가 HTTP 요청을 Command Handler에 위임하듯, 로직 없이 위임만 하고 예외를 그대로 던진다.

```python
# src/account/interface/task/account_task_controller.py — 실제 코드
class AccountTaskController:
    def __init__(self, handler: ApplyDailyInterestHandler) -> None:
        self._handler = handler

    async def apply_daily_interest(self, payload: dict) -> None:
        del payload  # 이 Task는 페이로드가 없다 — 그날 날짜 기준으로 실행한다
        await self._handler.execute(InterestConfig().daily_rate)
```

`TaskConsumer`(`src/task_queue/task_consumer.py`)가 `OutboxConsumer`와 동일한 구조로 `SqsConfig().task_queue_url`을 long polling하다가 메시지를 받으면 `taskType`(MessageAttributes)으로 `build_task_handlers()`(`src/task_queue/task_handlers.py`)가 조립한 dict에서 Task Controller 메서드를 찾아 호출한다.

```python
# src/task_queue/task_handlers.py — 실제 코드(발췌)
def build_task_handlers(session: AsyncSession) -> dict[str, TaskHandlerFn]:
    account_task_controller = AccountTaskController(ApplyDailyInterestHandler(account_repo))
    card_task_controller = CardTaskController(
        SendMonthlyCardStatementHandler(card_repo, account_adapter, payment_adapter)
    )
    return {
        "account.interest.apply": account_task_controller.apply_daily_interest,
        "card.statement.send": card_task_controller.send_monthly_statement,
    }
```

Domain Event의 `build_event_handlers()`(1:N 팬아웃, 값 타입이 `list[Callable]`)와 달리 Task는 `taskType`당 정확히 하나의 핸들러만 가지므로 값 타입이 단일 `Callable`이다(아래 "Task Queue vs Domain Event" 참고). 핸들러 성공 → `delete_message`(ack). 실패(또는 등록된 핸들러 없음) → 삭제하지 않는다 — FIFO 큐의 visibility timeout이 지나면 자동 재전달되고, `maxReceiveCount`를 넘기면 DLQ로 격리된다.

---

## Task Queue vs Domain Event — 이 저장소의 실제 구분

| | Domain Event (`src/outbox/`) | Task Queue (`src/task_queue/`) |
|---|---|---|
| 의미 단위 | 사실: `InterestPaid`, `CardStatementSent` | 명령: `account.interest.apply`, `card.statement.send` |
| 생산 주체 | Aggregate(`Account.apply_interest()`, `Card.send_statement()`) | Scheduler(APScheduler `@scheduled_job`) |
| 핸들러 수 | 1:N (`build_event_handlers()`의 값이 리스트가 아니라 여러 key로 팬아웃) | 1:1 (`build_task_handlers()`, taskType당 정확히 하나) |
| 큐 | `domain-events` 표준 큐 | `tasks.fifo` — 물리적으로 분리된 FIFO 큐(+ `tasks-dlq.fifo`) |
| 이 저장소의 예시 | `Account.apply_interest()` → `InterestPaid` → 알림 이메일 | `interest_scheduler`가 매일 자정 `account.interest.apply` Task를 enqueue |

정기 이자 지급 배치는 Task Queue와 Domain Event가 한 흐름 안에서 이어진다: **APScheduler(Cron)가 Task를 enqueue → `TaskConsumer`가 수신해 `AccountTaskController.apply_daily_interest()` 호출 → `ApplyDailyInterestHandler`가 ACTIVE 계좌를 순회하며 `Account.apply_interest()` 호출 → 이자가 지급된 계좌마다 `InterestPaid` Domain Event가 Outbox에 적재 → `OutboxPoller`/`OutboxConsumer`가 이를 다시 발행/수신해 `InterestPaidEventHandler`가 SES 알림을 보낸다.** 카드 사용내역 발송도 동일한 이중 구조다(`card.statement.send` Task → `SendMonthlyCardStatementHandler` → `Card.send_statement()` → `CardStatementSent` Domain Event → SES 알림). "배치를 트리거하는 것"과 "배치가 만든 사실을 알리는 것"이 서로 다른 큐·다른 의미 단위로 명확히 분리된다.

---

## MessageGroupId 전략 — 이 저장소의 실제 값

이 저장소의 두 배치는 둘 다 전역 Cron 배치라 `taskType` 자체를 그룹으로 쓴다(root 문서의 "Cron 전역 배치" 행과 동일).

| Task | `group_id` | `deduplication_id` |
|------|-----------|---------------------|
| `account.interest.apply` | `"account.interest"` | `f"account.interest.apply-{today.isoformat()}"` (날짜 단위) |
| `card.statement.send` | `"card.statement"` | `f"card.statement.send-{YYYY-MM}"` (월 단위) |

같은 날(또는 같은 달) 여러 인스턴스가 동시에 tick해도 `deduplication_id`가 동일하므로 FIFO 큐의 5분 dedup 윈도우 안에서 1건만 큐에 들어간다.

---

## 멱등성 — Level 1, Aggregate 필드로 구현

두 배치 모두 별도 Ledger 테이블 없이 Aggregate 자신의 필드로 "오늘/이번 달 이미 처리했는가"를 판단하는 **Level 1(본질적 멱등)**을 쓴다 — [domain-events.md#이벤트-핸들러-멱등성](domain-events.md#이벤트-핸들러-멱등성)과 동일한 모델.

```python
# src/account/domain/account.py — 실제 코드(발췌)
def apply_interest(self, daily_rate: Decimal, today: date) -> Transaction | None:
    if self.status != AccountStatus.ACTIVE:
        return None
    if self.last_interest_paid_at == today:
        return None  # 오늘 이미 처리됨 — 완전한 no-op

    self.last_interest_paid_at = today
    interest_amount = int((Decimal(self.balance.amount) * daily_rate).to_integral_value(rounding=ROUND_FLOOR))
    if interest_amount <= 0:
        return None  # 지급은 생략하지만 "오늘 처리했다"는 사실은 이미 기록됨

    ...
    self._events.append(InterestPaid(...))
    return transaction
```

```python
# src/card/domain/card.py — 실제 코드(발췌)
def send_statement(self, period: str, payment_count: int, total_amount: int, email: str) -> None:
    if self.last_statement_sent_month == period:
        return  # 이번 달 이미 발송됨 — 완전한 no-op
    self.last_statement_sent_month = period
    self._events.append(CardStatementSent(...))
```

`last_interest_paid_at`(`Account`)과 `last_statement_sent_month`(`Card`)는 각각 마이그레이션 `8f1c2a4e6d90_add_scheduling_task_queue_tables.py`가 `accounts`/`cards` 테이블에 추가한 nullable 컬럼이며, Repository의 `save()`가 매번 함께 저장한다. Task가 SQS at-least-once로 재전달되어 `ApplyDailyInterestHandler`/`SendMonthlyCardStatementHandler`가 같은 배치를 두 번 실행해도, 두 번째 실행은 모든 Aggregate에서 즉시 no-op을 반환하므로 이자/이메일이 중복되지 않는다 — `test_scheduling_e2e.py`의 재전달 시뮬레이션 테스트가 이를 직접 검증한다.

카드 통계 배치는 Domain Event 쪽에서도 한 겹 더 멱등성이 있다 — `CardStatementSentEventHandler`가 호출하는 Card 전용 `SesNotificationService`는 Account 쪽과 동일하게 Level 2(Ledger, `SentStatementEmailModel.outbox_event_id`)로 SES 중복 발송을 막는다. Aggregate 필드(Level 1)가 "언제 재처리를 막을지"를, Ledger(Level 2)가 "SES 발송 자체의 중복을 막을지"를 각각 담당하는 이중 방어다.

---

## payload 크기 — 이 저장소의 두 Task는 빈 payload

`account.interest.apply`/`card.statement.send` 모두 `payload={}`로 enqueue된다 — "오늘"/"이번 달"이라는 실행 시점 자체가 유일한 입력이고, 이는 `TaskController`가 아니라 Command Handler가 `date.today()`로 직접 계산한다. root 문서의 "작은 메타데이터만" 원칙이 극단적으로 적용된 사례다. 향후 특정 Aggregate ID를 대상으로 하는 Task(예: 특정 계좌만 재처리)가 생기면 `{ "account_id": "..." }` 수준의 최소 payload를 추가하면 된다.

---

## DLQ — 로컬 개발 환경 구성

`localstack/init-sqs.sh`가 `tasks-dlq.fifo` → `tasks.fifo`(RedrivePolicy `maxReceiveCount: 3`) 순서로 생성한다 — Domain Event 큐(`domain-events`/`domain-events-dlq`)와 동일한 구성을 FIFO로 재현한 것이다. `docker-compose.yml`/`.env.example`의 `SQS_TASK_QUEUE_URL`이 애플리케이션이 접속할 큐 URL이며, `src/config/sqs_config.py`의 `SqsConfig.task_queue_url`이 fail-fast 필수값으로 검증한다.

---

## 원칙

- **Scheduler는 Infrastructure 레이어, enqueue만**: `interest_scheduler.py`/`statement_scheduler.py`는 `TaskOutboxWriter.enqueue()` 호출 이상을 하지 않는다. `domain/`·`application/`에 스케줄링 데코레이터가 등장하면 harness의 `scheduler-in-infrastructure-only` 규칙이 잡아낸다.
- **적재는 Task Outbox 경유**: Cron tick의 단일 row insert 자체가 원자성을 만든다. `TaskOutboxPoller`가 이를 독립적으로 SQS FIFO로 발행한다.
- **Task Controller는 Interface 레이어**: `account_task_controller.py`/`card_task_controller.py`는 로직 없이 Command Handler에 위임하고 예외를 그대로 던진다 — `TaskConsumer`가 재시도/DLQ 여부를 결정한다.
- **핸들러 라우팅은 `src/task_queue/task_handlers.py`에 고정한다**: `build_task_handlers()`가 taskType당 하나의 핸들러를 조립하는 composition root다(Domain Event의 `build_event_handlers()`와 대칭 구조이되, 값 타입은 리스트가 아닌 단일 Callable).
- **멱등성은 Aggregate 필드(Level 1)로 구현되어 있다**: `Account.last_interest_paid_at`/`Card.last_statement_sent_month` — 재전달돼도 완전한 no-op이다.
- **Task 큐는 Domain Event 큐와 물리적으로 분리된 FIFO 큐다**: `tasks.fifo`/`tasks-dlq.fifo` — `MessageGroupId`/`MessageDeduplicationId`를 쓰는 인프라 차이가 있다.
- **Cron 예외는 명시적으로 로깅한다**: 두 Scheduler 모두 `try/except` + `logger.exception()`으로 감싸고, 재throw하지 않는다(다음 tick에서 재시도).

---

### 관련 문서

- [domain-events.md](domain-events.md) — `OutboxPoller`/`OutboxConsumer`의 실제 구현, Task Queue와 짝을 이루는 두 번째 비동기 경로(`InterestPaid`/`CardStatementSent` Domain Event)
- [layer-architecture.md](layer-architecture.md) — 레이어 역할, `Depends` 기반 DI
- [graceful-shutdown.md](graceful-shutdown.md) — `scheduler.shutdown(wait=True)`/Consumer의 안전한 종료
- [persistence.md](persistence.md) — 배치 작업의 트랜잭션 경계, `last_interest_paid_at`/`last_statement_sent_month` 컬럼
