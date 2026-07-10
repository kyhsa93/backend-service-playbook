from abc import ABC, abstractmethod


class SecretService(ABC):
    @abstractmethod
    async def get_secret(self, secret_id: str) -> dict: ...
