from abc import ABC, abstractmethod

from ...domain.card import CardDomainEvent


class NotificationService(ABC):
    @abstractmethod
    async def notify(self, event: CardDomainEvent, outbox_event_id: str) -> None: ...
