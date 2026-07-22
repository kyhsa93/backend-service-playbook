import asyncio

import bcrypt

from ...application.service.password_hasher import PasswordHasher

_SALT_ROUNDS = 12


class BcryptPasswordHasher(PasswordHasher):
    """A bcrypt-based implementation. Since bcrypt.hashpw/checkpw are synchronous and
    CPU-bound (hundreds of ms at 12 salt rounds), they are run in a worker thread via
    asyncio.to_thread so they don't block the event loop directly.
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
