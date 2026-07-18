from abc import ABC, abstractmethod

from ...domain.account import AccountDomainEvent


class NotificationService(ABC):
    @abstractmethod
    async def notify(self, event: AccountDomainEvent, outbox_event_id: str) -> None: ...
