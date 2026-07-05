from dataclasses import dataclass

from ...domain.errors import AccountNotFoundError
from ...domain.repository import AccountRepository
from ...infrastructure.notification.notification_service import NotificationService


@dataclass
class CloseAccountCommand:
    account_id: str
    requester_id: str


class CloseAccountHandler:

    def __init__(self, repo: AccountRepository, notification_service: NotificationService) -> None:
        self._repo = repo
        self._notification_service = notification_service

    async def execute(self, cmd: CloseAccountCommand) -> None:
        account = await self._repo.find_by_id(cmd.account_id, cmd.requester_id)
        if account is None:
            raise AccountNotFoundError(cmd.account_id)
        account.close()
        await self._repo.save(account)
        for event in account.pull_events():
            await self._notification_service.notify(event)
