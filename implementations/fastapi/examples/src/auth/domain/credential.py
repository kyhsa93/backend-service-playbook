from __future__ import annotations

from datetime import datetime

from ...common.generate_id import generate_id


class Credential:
    """자격 증명 Aggregate — userId + 해싱된 비밀번호. 평문 비밀번호는 Domain/Application 어디에도
    보관하지 않는다(해싱은 PasswordHasher Technical Service가 담당).
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
