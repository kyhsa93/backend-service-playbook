from abc import ABC, abstractmethod

from .credential import Credential


class CredentialRepository(ABC):
    """Credential Aggregate 전용 Repository. Query Handler가 없어(별도 조회 유스케이스 없음)
    account/card처럼 읽기 전용 ABC를 분리하지 않는다 — sign-up/sign-in 두 Command Handler가
    이 하나의 인터페이스로 조회/저장을 모두 수행한다.

    조회 메서드는 account/card 도메인과 동일하게 단일 `find_credentials(...)`로 통일한다
    (repository-pattern.md) — user_id 유일성 검사(sign-up)와 단건 조회(sign-in) 모두
    `take=1` + `user_id` 필터로 표현하고, 전용 `find_by_user_id`류 메서드는 두지 않는다.
    """

    @abstractmethod
    async def find_credentials(
        self, page: int, take: int, credential_id: str | None = None, user_id: str | None = None
    ) -> tuple[list[Credential], int]: ...

    @abstractmethod
    async def save_credential(self, credential: Credential) -> None: ...
