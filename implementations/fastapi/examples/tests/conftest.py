import asyncio
import json
import os
from collections.abc import Awaitable, Callable

# A test must pass validate_env() at main.py's import time — the actual connection target
# is swapped in by each e2e test's fixture via dependency_overrides, after it's started
# with testcontainers.
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://postgres:postgres@localhost:5432/account")
os.environ.setdefault("JWT_SECRET", "test-secret")
# SqsConfig() is also validated by validate_env() via fail-fast — the actual queue URL is
# swapped in by overwriting os.environ, after the fixture in an e2e test file that uses SQS
# (test_card_e2e.py/test_payment_e2e.py/test_notification_e2e.py/test_scheduling_e2e.py)
# starts the LocalStack container.
os.environ.setdefault("SQS_DOMAIN_EVENT_QUEUE_URL", "http://localhost:4566/000000000000/domain-events")
# The dedicated Task queue TaskOutboxPoller/TaskConsumer use — since main.py starts both of
# these in its lifespan, validate_env() requires this value in every e2e test file where
# `import main` happens.
os.environ.setdefault("SQS_TASK_QUEUE_URL", "http://localhost:4566/000000000000/tasks.fifo")

# An e2e test sends dozens of requests in a short time from the same client IP within the
# same process — using the production defaults as-is (RATE_LIMIT_WRITE=20/minute, etc.)
# would have slowapi return 429 even during a test, causing an unrelated failure. Since
# rate_limit_config.py reads its values from environment variables, they are overridden
# generously only here (see "Adjusting operational values" in rate-limiting.md).
os.environ.setdefault("RATE_LIMIT_DEFAULT", "100000/minute")
os.environ.setdefault("RATE_LIMIT_WRITE", "100000/minute")


async def wait_until(condition: Callable[[], Awaitable[bool]], timeout: float = 15.0, interval: float = 0.2) -> None:
    """Polls until the condition becomes True.

    Now that this has become a genuinely async pipeline going through Outbox →
    SQS (OutboxPoller, up to a 1-second tick) → OutboxConsumer (long polling,
    WaitTimeSeconds=5) and back, an e2e test can no longer assert immediately right after
    the API response. Since the actual round-trip delay observed in the LocalStack
    environment is on the order of a few seconds, the default timeout is set to a generous
    15 seconds.
    """
    elapsed = 0.0
    while elapsed < timeout:
        if await condition():
            return
        await asyncio.sleep(interval)
        elapsed += interval
    raise AssertionError(f"Condition not satisfied within {timeout} seconds")


def create_domain_event_queue(localstack) -> str:
    """Creates the `domain-events` queue + DLQ that OutboxPoller publishes to and
    OutboxConsumer receives from — the same RedrivePolicy configuration
    (maxReceiveCount=3) as `localstack/init-sqs.sh`. The return value is the queue URL the
    application connects to.
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
    """Starts an OutboxPoller/OutboxConsumer pointed at the test's session_factory
    (ephemeral Postgres) as background tasks — since `main.py`'s lifespan is never
    triggered in an e2e test run via ASGITransport, each e2e test file's fixture starts
    these two directly.
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
    """For the same reason as `start_outbox_background_tasks()`, starts
    TaskOutboxPoller/TaskConsumer directly as background tasks — the APScheduler-based
    Scheduler itself (interest_scheduler/statement_scheduler) is only started in
    `main.py`'s lifespan, so it never runs in an e2e test. Instead of waiting for a real
    Cron tick, a test calls `TaskOutboxWriter.enqueue()` directly to trigger the two
    scheduled paths (the regular interest payment/the monthly card-statement delivery).
    """
    from src.task_queue.task_consumer import TaskConsumer
    from src.task_queue.task_outbox_poller import TaskOutboxPoller

    poller = TaskOutboxPoller(session_factory)
    consumer = TaskConsumer(session_factory)
    return [asyncio.create_task(poller.run_forever()), asyncio.create_task(consumer.run_forever())]


stop_task_queue_background_tasks = stop_outbox_background_tasks


def create_task_queue(localstack) -> str:
    """Creates the `tasks.fifo` queue + DLQ that TaskOutboxPoller publishes to and
    TaskConsumer receives from — the same configuration as `localstack/init-sqs.sh` (FIFO,
    maxReceiveCount=3). The return value is the queue URL the application connects to."""
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
