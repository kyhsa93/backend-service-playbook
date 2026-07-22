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
            f"[Card] Your {event.period} card usage summary",
            f"During {event.period}, you made {event.payment_count} payment(s) on card "
            f"({event.card_id}) totaling {event.total_amount}.",
        )
    raise ValueError(f"Unknown event type: {type(event)!r}")


class SesNotificationService(NotificationService):
    """Sends Card domain events as SES emails, and records the send history in the DB.
    Follows exactly the same structure and the same idempotency strategy (a Level 2 Ledger
    based on outbox_event_id) as
    `account/infrastructure/notification/notification_service.py`
    (SesNotificationService) — reflecting, as a Card-BC-specific implementation, the
    requirement to "reuse/mimic the SES-based NotificationService pattern this repository
    already has."

    So that a notification-send failure (an SES error, etc.) never fails the batch itself,
    notify() catches every exception and only logs it, following exactly the same
    convention as Account.
    """

    def __init__(self, session: AsyncSession) -> None:
        self._session = session
        self._boto_session = aioboto3.Session()

    async def notify(self, event: CardDomainEvent, outbox_event_id: str) -> None:
        try:
            await self._send_and_record(event, outbox_event_id)
        except Exception:  # noqa: BLE001 - a notification failure must never affect batch processing
            logger.exception(
                "Failed to send card-statement email",
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
                "Event already sent — skipping duplicate send",
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
            "Card-statement email sent",
            extra={
                "event_type": event_type,
                "card_id": event.card_id,
                "recipient": recipient,
                "ses_message_id": ses_message_id,
                "outbox_event_id": outbox_event_id,
            },
        )
