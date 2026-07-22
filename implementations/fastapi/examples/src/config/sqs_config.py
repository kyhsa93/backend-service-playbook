from pydantic_settings import BaseSettings, SettingsConfigDict


class SqsConfig(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="SQS_")

    # The shared Domain/Integration Event queue that OutboxPoller publishes to and
    # OutboxConsumer receives from. Required — no default. Uses the same name as the
    # nestjs implementation's SQS_DOMAIN_EVENT_QUEUE_URL, keeping local-development
    # configuration (docker-compose.yml, .env.development) consistent across languages.
    domain_event_queue_url: str

    # The dedicated Task queue that TaskOutboxPoller publishes to and TaskConsumer receives
    # from — a FIFO queue physically separate from the Domain Event queue (see "Task Queue
    # vs Domain Event" in domain-events.md: "fact" vs. "command" are different units of
    # meaning, and there's also the infrastructure difference that only the Task queue is a
    # FIFO queue using MessageGroupId/MessageDeduplicationId). Required — no default.
    task_queue_url: str
