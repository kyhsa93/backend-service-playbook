from abc import ABC, abstractmethod

from .credential import Credential


class CredentialRepository(ABC):
    """The Repository dedicated to the Credential Aggregate. Since there is no Query
    Handler (no separate lookup use case), it doesn't split out a read-only ABC the way
    account/card do — the two Command Handlers, sign-up/sign-in, both perform lookup/save
    through this single interface.

    The lookup method is unified into a single `find_credentials(...)`, the same as the
    account/card domains (repository-pattern.md) — both the user_id uniqueness check
    (sign-up) and the single-item lookup (sign-in) are expressed with a `take=1` +
    `user_id` filter, and no dedicated `find_by_user_id`-style method is added.
    """

    @abstractmethod
    async def find_credentials(
        self, page: int, take: int, credential_id: str | None = None, user_id: str | None = None
    ) -> tuple[list[Credential], int]: ...

    @abstractmethod
    async def save_credential(self, credential: Credential) -> None: ...
