import asyncio

import bcrypt

from ...application.service.password_hasher import PasswordHasher

_SALT_ROUNDS = 12


class BcryptPasswordHasher(PasswordHasher):
    """bcrypt 기반 구현체. bcrypt.hashpw/checkpw는 동기·CPU-bound(salt round 12 기준 수백ms)라
    이벤트 루프를 직접 막지 않도록 asyncio.to_thread로 워커 스레드에서 실행한다.
    """

    async def hash(self, plain_password: str) -> str:
        return await asyncio.to_thread(self._hash_sync, plain_password)

    async def verify(self, plain_password: str, password_hash: str) -> bool:
        return await asyncio.to_thread(self._verify_sync, plain_password, password_hash)

    @staticmethod
    def _hash_sync(plain_password: str) -> str:
        return bcrypt.hashpw(plain_password.encode("utf-8"), bcrypt.gensalt(rounds=_SALT_ROUNDS)).decode("utf-8")

    @staticmethod
    def _verify_sync(plain_password: str, password_hash: str) -> bool:
        return bcrypt.checkpw(plain_password.encode("utf-8"), password_hash.encode("utf-8"))
