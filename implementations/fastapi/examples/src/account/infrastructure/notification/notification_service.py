from __future__ import annotations

import logging
import os

import aioboto3
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ....common.generate_id import generate_id
from ....config.aws_config import AwsConfig
from ...application.service.notification_service import NotificationService
from ...domain.account import AccountDomainEvent
from ...domain.events import (
    AccountClosed,
    AccountCreated,
    AccountReactivated,
    AccountSuspended,
    InterestPaid,
    MoneyDeposited,
    MoneyWithdrawn,
)
from .sent_email_model import SentEmailModel

logger = logging.getLogger(__name__)

DEFAULT_SENDER_EMAIL = "no-reply@backend-service-playbook.example.com"


def _render(event: AccountDomainEvent) -> tuple[str, str]:
    if isinstance(event, AccountCreated):
        return (
            "[Account] Your account has been created",
            f"An account in {event.currency} has been created. (Account ID: {event.account_id})",
        )
    if isinstance(event, MoneyDeposited):
        return (
            "[Account] Your deposit is complete",
            f"{event.amount.amount} {event.amount.currency} has been deposited. "
            f"Balance after deposit: {event.balance_after.amount} {event.balance_after.currency}",
        )
    if isinstance(event, MoneyWithdrawn):
        return (
            "[Account] Your withdrawal is complete",
            f"{event.amount.amount} {event.amount.currency} has been withdrawn. "
            f"Balance after withdrawal: {event.balance_after.amount} {event.balance_after.currency}",
        )
    if isinstance(event, InterestPaid):
        return (
            "[Account] Interest has been paid",
            f"Interest of {event.amount.amount} {event.amount.currency} has been paid. "
            f"Balance after payment: {event.balance_after.amount} {event.balance_after.currency}",
        )
    if isinstance(event, AccountSuspended):
        return "[Account] Your account has been suspended", f"Account ({event.account_id}) has been suspended."
    if isinstance(event, AccountReactivated):
        return "[Account] Your account has been reactivated", f"Account ({event.account_id}) has been reactivated."
    if isinstance(event, AccountClosed):
        return "[Account] Your account has been closed", f"Account ({event.account_id}) has been closed."
    raise ValueError(f"Unknown event type: {type(event)!r}")


class SesNotificationService(NotificationService):
    """Sends Account domain events as SES emails, and records the send history in the DB.

    So that a notification-send failure never affects the account command itself, this
    service's sole public method, notify(), catches every exception raised internally and
    only logs it, without propagating it.

    Since the Outbox guarantees at-least-once delivery (the same event can be delivered
    twice via a retry), Level 2 (Ledger) idempotency is applied, checking whether the event
    was already processed based on `outbox_event_id` before sending — `SentEmailModel`
    doubles as both the send-history Entity and the Ledger (see "Event Handler Idempotency"
    in domain-events.md).
    """

    def __init__(self, session: AsyncSession) -> None:
        self._session = session
        self._boto_session = aioboto3.Session()

    async def notify(self, event: AccountDomainEvent, outbox_event_id: str) -> None:
        try:
            await self._send_and_record(event, outbox_event_id)
        except Exception:  # noqa: BLE001 - a notification failure must never affect command processing
            logger.exception(
                "Failed to send notification email",
                extra={"event_type": type(event).__name__, "account_id": event.account_id},
            )

    async def _send_and_record(self, event: AccountDomainEvent, outbox_event_id: str) -> None:
        event_type = type(event).__name__

        already_sent = (
            await self._session.execute(select(SentEmailModel).where(SentEmailModel.outbox_event_id == outbox_event_id))
        ).scalar_one_or_none()
        if already_sent is not None:
            logger.info(
                "Event already sent — skipping duplicate send",
                extra={"event_type": event_type, "account_id": event.account_id, "outbox_event_id": outbox_event_id},
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
            SentEmailModel(
                sent_email_id=generate_id(),
                account_id=event.account_id,
                event_type=event_type,
                recipient=recipient,
                subject=subject,
                ses_message_id=ses_message_id,
                outbox_event_id=outbox_event_id,
            )
        )
        await self._session.flush()

        logger.info(
            "Notification email sent",
            extra={
                "event_type": event_type,
                "account_id": event.account_id,
                "recipient": recipient,
                "ses_message_id": ses_message_id,
                "outbox_event_id": outbox_event_id,
            },
        )
