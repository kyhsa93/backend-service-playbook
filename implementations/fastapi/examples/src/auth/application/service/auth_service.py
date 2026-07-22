from abc import ABC, abstractmethod


class AuthService(ABC):
    @abstractmethod
    def issue_token(self, user_id: str) -> str: ...

    @abstractmethod
    def verify_token(self, token: str) -> str:
        """Returns user_id on successful verification, and raises InvalidTokenError on failure."""
