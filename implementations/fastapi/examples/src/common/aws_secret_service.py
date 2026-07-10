from __future__ import annotations

import json
import time

import aioboto3

from ..config.aws_config import AwsConfig
from .secret_service import SecretService


class AwsSecretService(SecretService):
    def __init__(self, ttl_seconds: int = 300) -> None:
        self._boto_session = aioboto3.Session()
        self._ttl_seconds = ttl_seconds
        self._cache: dict[str, tuple[dict, float]] = {}

    async def get_secret(self, secret_id: str) -> dict:
        cached = self._cache.get(secret_id)
        if cached is not None:
            value, expires_at = cached
            if expires_at > time.monotonic():
                return value

        async with self._boto_session.client(
            "secretsmanager", **AwsConfig().client_kwargs()  # type: ignore[call-arg]
        ) as client:
            response = await client.get_secret_value(SecretId=secret_id)

        value = json.loads(response["SecretString"])
        self._cache[secret_id] = (value, time.monotonic() + self._ttl_seconds)
        return value
