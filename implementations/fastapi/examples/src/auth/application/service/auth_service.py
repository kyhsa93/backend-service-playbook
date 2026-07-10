from abc import ABC, abstractmethod


class AuthService(ABC):
    @abstractmethod
    def issue_token(self, user_id: str) -> str: ...

    @abstractmethod
    def verify_token(self, token: str) -> str:
        """검증 성공 시 user_id를 반환하고, 실패 시 InvalidTokenError를 raise한다."""
