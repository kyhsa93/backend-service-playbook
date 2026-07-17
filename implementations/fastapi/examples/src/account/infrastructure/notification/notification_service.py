from __future__ import annotations

import logging
import os

import aioboto3
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
    MoneyDeposited,
    MoneyWithdrawn,
)
from .sent_email_model import SentEmailModel

logger = logging.getLogger(__name__)

DEFAULT_SENDER_EMAIL = "no-reply@backend-service-playbook.example.com"


def _render(event: AccountDomainEvent) -> tuple[str, str]:
    if isinstance(event, AccountCreated):
        return (
            "[Account] 계좌가 생성되었습니다",
            f"{event.currency} 통화의 계좌가 생성되었습니다. (계좌 ID: {event.account_id})",
        )
    if isinstance(event, MoneyDeposited):
        return (
            "[Account] 입금이 완료되었습니다",
            f"{event.amount.amount} {event.amount.currency}이 입금되었습니다. "
            f"입금 후 잔액: {event.balance_after.amount} {event.balance_after.currency}",
        )
    if isinstance(event, MoneyWithdrawn):
        return (
            "[Account] 출금이 완료되었습니다",
            f"{event.amount.amount} {event.amount.currency}이 출금되었습니다. "
            f"출금 후 잔액: {event.balance_after.amount} {event.balance_after.currency}",
        )
    if isinstance(event, AccountSuspended):
        return "[Account] 계좌가 정지되었습니다", f"계좌({event.account_id})가 정지되었습니다."
    if isinstance(event, AccountReactivated):
        return "[Account] 계좌가 재개되었습니다", f"계좌({event.account_id})가 재개되었습니다."
    if isinstance(event, AccountClosed):
        return "[Account] 계좌가 해지되었습니다", f"계좌({event.account_id})가 해지되었습니다."
    raise ValueError(f"알 수 없는 이벤트 타입: {type(event)!r}")


class SesNotificationService(NotificationService):
    """Account 도메인 이벤트를 SES 이메일로 발송하고, 발송 내역을 DB에 기록한다.

    알림 발송 실패가 계좌 커맨드 자체에 영향을 주지 않도록, 이 서비스의 유일한
    공개 메서드인 notify()는 내부에서 발생하는 모든 예외를 잡아 로깅만 하고 삼킨다.
    """

    def __init__(self, session: AsyncSession) -> None:
        self._session = session
        self._boto_session = aioboto3.Session()

    async def notify(self, event: AccountDomainEvent) -> None:
        try:
            await self._send_and_record(event)
        except Exception:  # noqa: BLE001 - 알림 실패는 커맨드 처리에 영향을 주면 안 된다
            logger.exception(
                "알림 이메일 발송 실패",
                extra={"event_type": type(event).__name__, "account_id": event.account_id},
            )

    async def _send_and_record(self, event: AccountDomainEvent) -> None:
        event_type = type(event).__name__
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
            )
        )
        await self._session.flush()

        logger.info(
            "알림 이메일 발송됨",
            extra={
                "event_type": event_type,
                "account_id": event.account_id,
                "recipient": recipient,
                "ses_message_id": ses_message_id,
            },
        )
