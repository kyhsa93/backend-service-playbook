from __future__ import annotations

import json
import logging

from anthropic import AsyncAnthropic

from ...common.secret_service import SecretService
from ...config.llm_config import get_anthropic_api_key, get_refund_classifier_model
from ..application.service.refund_reason_classifier import RefundReasonClassifier
from ..domain.refund_reason_classification import RefundReasonCategory, RefundReasonClassification

logger = logging.getLogger(__name__)

CATEGORIES = [category.value for category in RefundReasonCategory]

# Used whenever the classification can't be trusted (API error, refusal, malformed output). A
# neutral 'other'/no-fraud-signal result never blocks the refund flow on its own —
# RefundEligibilityService's other checks still run against it.
FALLBACK_CLASSIFICATION = RefundReasonClassification(category=RefundReasonCategory.OTHER, fraud_risk_score=0.0)

SYSTEM_PROMPT = (
    "You classify a customer refund request's free-text reason. Respond only through the given "
    "schema. Base fraud_risk_score purely on linguistic signals in the text itself (vagueness, "
    "internal inconsistency, urgency/pressure language, or an admission unrelated to the "
    "product) — never infer a high score just because the category is fraud_suspected, and "
    "never infer a low score just because it isn't."
)


class RefundReasonClassifierImpl(RefundReasonClassifier):
    def __init__(self, secret_service: SecretService) -> None:
        self._secret_service = secret_service
        self._client: AsyncAnthropic | None = None

    # The API key is looked up from Secrets Manager only in production, exactly like
    # config/jwt_config.py's secret — every other environment (development/test) uses the
    # environment variable directly with no network call. Unlike jwt_config.py (read at
    # fail-fast validation time before any DI container exists), this lookup happens lazily on
    # first use, inside a Depends-constructed Technical Service, via the injected
    # SecretService (see docs/architecture/secret-manager.md). The APP_ENV branch and the
    # environment-variable read itself both live in config/llm_config.py, not here.
    async def _get_client(self) -> AsyncAnthropic:
        if self._client is not None:
            return self._client
        api_key = await get_anthropic_api_key(self._secret_service)
        self._client = AsyncAnthropic(api_key=api_key)
        return self._client

    async def classify(self, reason: str) -> RefundReasonClassification:
        try:
            client = await self._get_client()
            response = await client.messages.create(
                model=get_refund_classifier_model(),
                max_tokens=256,
                system=SYSTEM_PROMPT,
                messages=[{"role": "user", "content": reason}],
                output_config={
                    "format": {
                        "type": "json_schema",
                        "schema": {
                            "type": "object",
                            "properties": {
                                "category": {"type": "string", "enum": CATEGORIES},
                                "fraud_risk_score": {"type": "number"},
                            },
                            "required": ["category", "fraud_risk_score"],
                            "additionalProperties": False,
                        },
                    }
                },
            )

            if response.stop_reason == "refusal":
                return FALLBACK_CLASSIFICATION

            text_block = next((block for block in response.content if block.type == "text"), None)
            if text_block is None:
                return FALLBACK_CLASSIFICATION

            parsed = json.loads(text_block.text)
            category = parsed.get("category")
            if category not in CATEGORIES:
                return FALLBACK_CLASSIFICATION

            fraud_risk_score = min(1.0, max(0.0, float(parsed.get("fraud_risk_score", 0.0))))
            return RefundReasonClassification(
                category=RefundReasonCategory(category), fraud_risk_score=fraud_risk_score
            )
        except Exception:  # noqa: BLE001 - a classification failure must never block a refund request
            # A classification failure is a technical-infrastructure concern, not a domain
            # error — it must never block a refund request. Swallow it here at the boundary
            # and fall back.
            logger.warning("Refund reason classification failed, using fallback", exc_info=True)
            return FALLBACK_CLASSIFICATION
