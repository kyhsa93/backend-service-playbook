import asyncio
import json
import os
from collections.abc import Awaitable, Callable

# 테스트는 main.py import 시점에 validate_env()를 통과해야 한다 — 실제 접속 대상은
# 각 e2e 테스트가 testcontainers로 띄운 뒤 fixture에서 dependency_overrides로 교체한다.
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://postgres:postgres@localhost:5432/account")
os.environ.setdefault("JWT_SECRET", "test-secret")
# SqsConfig()도 validate_env()가 fail-fast로 검증한다 — 실제 큐 URL은 SQS를 쓰는 e2e
# 테스트 파일(test_card_e2e.py/test_payment_e2e.py/test_notification_e2e.py/
# test_scheduling_e2e.py)의 fixture가 LocalStack 컨테이너를 띄운 뒤 os.environ을 덮어써서
# 교체한다.
os.environ.setdefault("SQS_DOMAIN_EVENT_QUEUE_URL", "http://localhost:4566/000000000000/domain-events")
# TaskOutboxPoller/TaskConsumer가 쓰는 전용 Task 큐 — main.py가 이 둘을 lifespan에서 기동하므로
# `import main`이 일어나는 모든 e2e 테스트 파일에서 validate_env()가 이 값을 요구한다.
os.environ.setdefault("SQS_TASK_QUEUE_URL", "http://localhost:4566/000000000000/tasks.fifo")

# e2e 테스트는 같은 프로세스 안에서 같은 클라이언트 IP로 수십 개 요청을 짧은 시간에 보낸다 —
# 운영 기본값(RATE_LIMIT_WRITE=20/minute 등)을 그대로 쓰면 slowapi가 테스트 도중에도
# 429를 반환해 무관한 실패를 만든다. rate_limit_config.py가 환경 변수로 값을 읽으므로
# 여기서만 넉넉하게 override한다(rate-limiting.md "운영값 조정" 참고).
os.environ.setdefault("RATE_LIMIT_DEFAULT", "100000/minute")
os.environ.setdefault("RATE_LIMIT_WRITE", "100000/minute")


async def wait_until(condition: Callable[[], Awaitable[bool]], timeout: float = 15.0, interval: float = 0.2) -> None:
    """조건이 True가 될 때까지 폴링한다.

    Outbox → SQS(OutboxPoller, 최대 1초 tick) → OutboxConsumer(long polling,
    WaitTimeSeconds=5) 왕복을 거치는 진짜 비동기 파이프라인으로 바뀌었으므로, e2e 테스트는
    더 이상 API 응답 직후 즉시 assert할 수 없다. LocalStack 환경에서 관측되는 실제 왕복
    지연은 몇 초 수준이라 기본 타임아웃은 여유를 둔 15초로 잡는다.
    """
    elapsed = 0.0
    while elapsed < timeout:
        if await condition():
            return
        await asyncio.sleep(interval)
        elapsed += interval
    raise AssertionError(f"조건이 {timeout}초 내에 충족되지 않음")


def create_domain_event_queue(localstack) -> str:
    """OutboxPoller가 발행하고 OutboxConsumer가 수신하는 `domain-events` 큐 + DLQ를
    생성한다 — `localstack/init-sqs.sh`와 동일한 RedrivePolicy(maxReceiveCount=3) 구성.
    반환값은 애플리케이션이 접속할 큐 URL이다.
    """
    client = localstack.get_client("sqs")
    dlq = client.create_queue(QueueName="domain-events-dlq")
    dlq_arn = client.get_queue_attributes(QueueUrl=dlq["QueueUrl"], AttributeNames=["QueueArn"])["Attributes"][
        "QueueArn"
    ]
    main_queue = client.create_queue(
        QueueName="domain-events",
        Attributes={
            "RedrivePolicy": json.dumps({"deadLetterTargetArn": dlq_arn, "maxReceiveCount": "3"}),
        },
    )
    return main_queue["QueueUrl"]


def start_outbox_background_tasks(session_factory) -> list[asyncio.Task]:
    """테스트의 session_factory(ephemeral Postgres)를 가리키는 OutboxPoller/OutboxConsumer를
    백그라운드 task로 시작한다 — `main.py`의 lifespan은 ASGITransport로 구동되는 e2e
    테스트에서는 트리거되지 않으므로, 각 e2e 테스트 파일의 fixture가 직접 이 둘을 띄운다.
    """
    from src.outbox.outbox_consumer import OutboxConsumer
    from src.outbox.outbox_poller import OutboxPoller

    poller = OutboxPoller(session_factory)
    consumer = OutboxConsumer(session_factory)
    return [asyncio.create_task(poller.run_forever()), asyncio.create_task(consumer.run_forever())]


async def stop_outbox_background_tasks(tasks: list[asyncio.Task]) -> None:
    for task in tasks:
        task.cancel()
    for task in tasks:
        try:
            await task
        except asyncio.CancelledError:
            pass


def start_task_queue_background_tasks(session_factory) -> list[asyncio.Task]:
    """`start_outbox_background_tasks()`와 동일한 이유로, TaskOutboxPoller/TaskConsumer를
    직접 백그라운드 task로 띄운다 — APScheduler 기반 Scheduler 자체(interest_scheduler/
    statement_scheduler)는 `main.py`의 lifespan에서만 시작되므로 e2e 테스트에서는 실행되지
    않는다. 테스트는 실제 Cron tick을 기다리는 대신 `TaskOutboxWriter.enqueue()`를 직접
    호출해 스케줄된 두 경로(정기 이자 지급/매월 카드 사용내역 발송)를 트리거한다.
    """
    from src.task_queue.task_consumer import TaskConsumer
    from src.task_queue.task_outbox_poller import TaskOutboxPoller

    poller = TaskOutboxPoller(session_factory)
    consumer = TaskConsumer(session_factory)
    return [asyncio.create_task(poller.run_forever()), asyncio.create_task(consumer.run_forever())]


stop_task_queue_background_tasks = stop_outbox_background_tasks


def create_task_queue(localstack) -> str:
    """TaskOutboxPoller가 발행하고 TaskConsumer가 수신하는 `tasks.fifo` 큐 + DLQ를 생성한다
    — `localstack/init-sqs.sh`와 동일한 구성(FIFO, maxReceiveCount=3). 반환값은 애플리케이션이
    접속할 큐 URL이다."""
    client = localstack.get_client("sqs")
    dlq = client.create_queue(QueueName="tasks-dlq.fifo", Attributes={"FifoQueue": "true"})
    dlq_arn = client.get_queue_attributes(QueueUrl=dlq["QueueUrl"], AttributeNames=["QueueArn"])["Attributes"][
        "QueueArn"
    ]
    main_queue = client.create_queue(
        QueueName="tasks.fifo",
        Attributes={
            "FifoQueue": "true",
            "RedrivePolicy": json.dumps({"deadLetterTargetArn": dlq_arn, "maxReceiveCount": "3"}),
        },
    )
    return main_queue["QueueUrl"]
