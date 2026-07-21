from __future__ import annotations

from datetime import datetime

from sqlalchemy import CHAR
from sqlalchemy.orm import Mapped, mapped_column

from ..account.infrastructure.persistence.account_repository import Base


class TaskOutboxModel(Base):
    """Task Outbox — Domain Event의 `OutboxModel`(src/outbox/outbox_model.py)과 개념적으로
    분리된 테이블이다. Domain Event가 "사실(past): X가 일어났다"를 나르는 반면, Task는
    "명령(imperative): X를 수행하라"를 나른다(domain-events.md "Task Queue vs Domain Event").
    Scheduler(Cron)가 트랜잭션 문맥 없이 호출하는 지점이라, 이 테이블에 대한 단일 row
    insert 자체가 원자성을 만든다(scheduling.md "Task Outbox 패턴").
    """

    __tablename__ = "task_outbox"

    task_id: Mapped[str] = mapped_column(CHAR(32), primary_key=True)
    task_type: Mapped[str]
    payload: Mapped[str]
    # FIFO Task 큐의 병렬성 경계 — 같은 group_id는 순차 처리된다(scheduling.md "MessageGroupId
    # 전략"). 이 저장소의 두 배치는 둘 다 전역 Cron 배치라 taskType 자체를 그룹으로 쓴다.
    group_id: Mapped[str]
    # 날짜 기반 dedup — 같은 날(또는 같은 달) 여러 인스턴스가 동시에 enqueue해도 FIFO 큐의
    # 5분 dedup 윈도우 안에서 1건만 들어간다(scheduling.md "Cron 다중 인스턴스 안전성").
    deduplication_id: Mapped[str]
    processed: Mapped[bool] = mapped_column(default=False)
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
