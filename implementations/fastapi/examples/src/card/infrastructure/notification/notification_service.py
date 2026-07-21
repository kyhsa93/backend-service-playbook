from __future__ import annotations

import logging
import os

import aioboto3
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ....common.generate_id import generate_id
from ....config.aws_config import AwsConfig
from ...application.service.notification_service import NotificationService
from ...domain.card import CardDomainEvent
from ...domain.events import CardStatementSent
from .sent_statement_email_model import SentStatementEmailModel

logger = logging.getLogger(__name__)

DEFAULT_SENDER_EMAIL = "no-reply@backend-service-playbook.example.com"


def _render(event: CardDomainEvent) -> tuple[str, str]:
    if isinstance(event, CardStatementSent):
        return (
            f"[Card] {event.period} 카드 이용내역 안내",
            f"{event.period} 동안 카드({event.card_id})로 {event.payment_count}건, "
            f"총 {event.total_amount}원을 결제하셨습니다.",
        )
    raise ValueError(f"알 수 없는 이벤트 타입: {type(event)!r}")


class SesNotificationService(NotificationService):
    """Card 도메인 이벤트를 SES 이메일로 발송하고, 발송 내역을 DB에 기록한다.
    `account/infrastructure/notification/notification_service.py`(SesNotificationService)와
    동일한 구조·동일한 멱등성 전략(outbox_event_id 기준 Level 2 Ledger)을 그대로 따른다 —
    "이 저장소가 이미 갖고 있는 SES 기반 NotificationService 패턴을 재사용/모방하라"는
    요구를 Card BC 전용 구현으로 반영한 것이다.

    알림 발송 실패가(SES 오류 등) 배치 자체를 실패시키지 않도록, notify()는 모든 예외를
    잡아 로깅만 하고 삼킨다 — Account의 동일한 관례를 그대로 따른다.
    """

    def __init__(self, session: AsyncSession) -> None:
        self._session = session
        self._boto_session = aioboto3.Session()

    async def notify(self, event: CardDomainEvent, outbox_event_id: str) -> None:
        try:
            await self._send_and_record(event, outbox_event_id)
        except Exception:  # noqa: BLE001 - 알림 실패는 배치 처리에 영향을 주면 안 된다
            logger.exception(
                "카드 사용내역 이메일 발송 실패",
                extra={"event_type": type(event).__name__, "card_id": event.card_id},
            )

    async def _send_and_record(self, event: CardDomainEvent, outbox_event_id: str) -> None:
        event_type = type(event).__name__

        already_sent = (
            await self._session.execute(
                select(SentStatementEmailModel).where(SentStatementEmailModel.outbox_event_id == outbox_event_id)
            )
        ).scalar_one_or_none()
        if already_sent is not None:
            logger.info(
                "이미 발송된 이벤트 — 중복 발송을 건너뜀",
                extra={"event_type": event_type, "card_id": event.card_id, "outbox_event_id": outbox_event_id},
            )
            return

        subject, body = _render(event)
        recipient = event.email

        async with self._boto_session.client(
            "ses",
            **AwsConfig().client_kwargs(),  # type: ignore[call-arg]
        ) as ses_client:
            response = await ses_client.send_email(
                Source=os.getenv("SES_SENDER_EMAIL", DEFAULT_SENDER_EMAIL),
                Destination={"ToAddresses": [recipient]},
                Message={
                    "Subject": {"Data": subject, "Charset": "UTF-8"},
                    "Body": {"Text": {"Data": body, "Charset": "UTF-8"}},
                },
            )

        ses_message_id = response["MessageId"]

        self._session.add(
            SentStatementEmailModel(
                sent_email_id=generate_id(),
                card_id=event.card_id,
                event_type=event_type,
                recipient=recipient,
                subject=subject,
                ses_message_id=ses_message_id,
                outbox_event_id=outbox_event_id,
            )
        )
        await self._session.flush()

        logger.info(
            "카드 사용내역 이메일 발송됨",
            extra={
                "event_type": event_type,
                "card_id": event.card_id,
                "recipient": recipient,
                "ses_message_id": ses_message_id,
                "outbox_event_id": outbox_event_id,
            },
        )
