from abc import ABC, abstractmethod

from .credential import Credential


class CredentialRepository(ABC):
    """Credential Aggregate 전용 Repository. Query Handler가 없어(별도 조회 유스케이스 없음)
    account/card처럼 읽기 전용 ABC를 분리하지 않는다 — sign-up/sign-in 두 Command Handler가
    이 하나의 인터페이스로 조회/저장을 모두 수행한다.
    """

    @abstractmethod
    async def find_by_user_id(self, user_id: str) -> Credential | None: ...

    @abstractmethod
    async def save(self, credential: Credential) -> None: ...
