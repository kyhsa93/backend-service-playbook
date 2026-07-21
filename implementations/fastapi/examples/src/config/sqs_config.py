from pydantic_settings import BaseSettings, SettingsConfigDict


class SqsConfig(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="SQS_")

    # OutboxPoller가 발행하고 OutboxConsumer가 수신하는 공유 Domain/Integration Event 큐.
    # 필수값 — 기본값 없음. nestjs 구현의 SQS_DOMAIN_EVENT_QUEUE_URL과 동일한 이름을 써서
    # 언어 간 로컬 개발 설정(docker-compose.yml, .env.development)을 일관되게 유지한다.
    domain_event_queue_url: str

    # TaskOutboxPoller가 발행하고 TaskConsumer가 수신하는 전용 Task 큐 — Domain Event 큐와
    # 물리적으로 분리된 FIFO 큐다(domain-events.md "Task Queue vs Domain Event": "사실" vs
    # "명령"은 의미 단위가 다르고, Task 큐만 MessageGroupId/MessageDeduplicationId를 쓰는
    # FIFO 큐라는 인프라 차이도 있다). 필수값 — 기본값 없음.
    task_queue_url: str
