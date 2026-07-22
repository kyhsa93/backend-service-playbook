from abc import ABC, abstractmethod


class PasswordHasher(ABC):
    """A Technical Service for password hashing/verification — exposes only an abstraction
    so Domain/Application never depend on a concrete library such as bcrypt (the same
    pattern as the account domain's NotificationService).
    """

    @abstractmethod
    async def hash(self, plain_password: str) -> str: ...

    @abstractmethod
    async def verify(self, plain_password: str, password_hash: str) -> bool: ...
