from __future__ import annotations

from datetime import datetime

from ...common.generate_id import generate_id


class Credential:
    """The Credential Aggregate — userId + a hashed password. A plaintext password is never
    stored anywhere in Domain/Application (hashing is handled by the PasswordHasher
    Technical Service).
    """

    def __init__(self, credential_id: str, user_id: str, password_hash: str, created_at: datetime) -> None:
        self.credential_id = credential_id
        self.user_id = user_id
        self.password_hash = password_hash
        self.created_at = created_at

    @classmethod
    def create(cls, user_id: str, password_hash: str) -> Credential:
        return cls(
            credential_id=generate_id(),
            user_id=user_id,
            password_hash=password_hash,
            created_at=datetime.utcnow(),
        )
