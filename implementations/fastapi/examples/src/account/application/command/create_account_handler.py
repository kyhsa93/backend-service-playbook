from dataclasses import dataclass

from ...domain.account import Account
from ...domain.repository import AccountRepository
from ...infrastructure.notification.notification_service import NotificationService


@dataclass
class CreateAccountCommand:
    requester_id: str
    currency: str
    email: str


class CreateAccountHandler:

    def __init__(self, repo: AccountRepository, notification_service: NotificationService) -> None:
        self._repo = repo
        self._notification_service = notification_service

    async def execute(self, cmd: CreateAccountCommand) -> Account:
        account = Account.create(cmd.requester_id, cmd.currency, cmd.email)
        await self._repo.save(account)
        for event in account.pull_events():
            await self._notification_service.notify(event)
        return account
