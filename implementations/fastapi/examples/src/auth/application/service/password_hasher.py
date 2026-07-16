from abc import ABC, abstractmethod


class PasswordHasher(ABC):
    """비밀번호 해싱/검증 Technical Service — Domain/Application이 bcrypt 같은 구체 라이브러리에
    의존하지 않도록 abstraction만 노출한다(account 도메인의 NotificationService와 동일한 패턴).
    """

    @abstractmethod
    async def hash(self, plain_password: str) -> str: ...

    @abstractmethod
    async def verify(self, plain_password: str, password_hash: str) -> bool: ...
