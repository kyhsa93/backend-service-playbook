from __future__ import annotations

import os
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from ..common.secret_service import SecretService

DEFAULT_REFUND_CLASSIFIER_MODEL = "claude-opus-4-8"


def get_refund_classifier_model() -> str:
    return os.getenv("REFUND_CLASSIFIER_MODEL", DEFAULT_REFUND_CLASSIFIER_MODEL)


# Looks up the Anthropic API key from Secrets Manager only in production — every other
# environment (development/test) uses the environment variable directly, with no network
# call. Same APP_ENV-gating convention/polarity as main.py's JWT-secret lookup ("cloud if
# production" — see docs/architecture/secret-manager.md), just encapsulated here (all
# os.environ/os.getenv access must live in src/config/*_config.py — see config.md) rather
# than in the calling Technical Service implementation.
async def get_anthropic_api_key(secret_service: "SecretService") -> str:
    if os.getenv("APP_ENV") != "production":
        return os.getenv("ANTHROPIC_API_KEY", "dev-anthropic-key")
    secret = await secret_service.get_secret("app/anthropic")
    return secret["secret"]
