from __future__ import annotations

import json
import logging

import httpx

from ...config.llm_config import get_ollama_base_url, get_refund_classifier_model
from ..application.service.refund_reason_classifier import RefundReasonClassifier
from ..domain.refund_reason_classification import RefundReasonCategory, RefundReasonClassification

logger = logging.getLogger(__name__)

CATEGORIES = [category.value for category in RefundReasonCategory]

# Used whenever the classification can't be trusted (Ollama unreachable, malformed output). A
# neutral 'other'/no-fraud-signal result never blocks the refund flow on its own —
# RefundEligibilityService's other checks still run against it.
FALLBACK_CLASSIFICATION = RefundReasonClassification(category=RefundReasonCategory.OTHER, fraud_risk_score=0.0)

# Deliberately explicit and example-anchored — the classifier runs on a small, self-hosted
# model (qwen2.5:1.5b) that, tested live against this exact prompt shape, otherwise conflates
# ordinary billing complaints ("charged twice, refund the duplicate") with fraud_suspected.
# A calm, single-issue complaint is never fraud_suspected on its own; only report fraud when the
# text itself shows deception or denies placing the order.
SYSTEM_PROMPT = (
    "You classify a customer refund request's free-text reason into exactly one category and a "
    "fraud-risk score from 0 to 1. Respond only through the given schema.\n"
    "Categories: defective_product (item broken/damaged/malfunctioning), not_as_described (wrong item or "
    "mismatched description), duplicate_charge (billed more than once for the same order), changed_mind (no "
    "longer wants the item, no product issue), fraud_suspected (the customer explicitly states they never placed "
    "the order, or the message itself shows deception/inconsistency), other.\n"
    "A plain, calm complaint about being billed twice is duplicate_charge with a LOW fraud score near 0 — it is "
    "not fraud_suspected. Only use fraud_suspected for signs of deception, not for an ordinary billing complaint."
)

# Ollama's `format` field takes a raw JSON Schema and constrains decoding to match it (grammar-
# based — this guarantees syntactically valid JSON matching this shape regardless of model
# size; it does NOT guarantee the category/score judgment itself is reliable at small sizes,
# which is why the system prompt above is unusually explicit and example-anchored).
RESPONSE_FORMAT = {
    "type": "object",
    "properties": {
        "category": {"type": "string", "enum": CATEGORIES},
        "fraudRiskScore": {"type": "number"},
    },
    "required": ["category", "fraudRiskScore"],
    "additionalProperties": False,
}


# A Technical Service (see root docs/architecture/domain-service.md) wrapping a self-hosted LLM
# call. Ollama (docker-compose.yml's ollama/ollama-init services) runs the open-source
# qwen2.5:1.5b model locally — no external API, no API key. Talks to Ollama's native /api/chat
# endpoint over plain HTTP rather than a vendor SDK, since Ollama has no official Python client.
# qwen2.5:1.5b (not the smaller 0.5b variant) was chosen after live-testing both: 0.5b
# misclassified plain billing complaints ("charged twice, refund the duplicate") as
# fraud_suspected with fraudRiskScore 1.0, which would wrongly reject a legitimate refund at
# the 0.7 threshold below. 1.5b is meaningfully more reliable while still under ~1GB.
class RefundReasonClassifierImpl(RefundReasonClassifier):
    async def classify(self, reason: str) -> RefundReasonClassification:
        try:
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{get_ollama_base_url()}/api/chat",
                    json={
                        "model": get_refund_classifier_model(),
                        "stream": False,
                        "messages": [
                            {"role": "system", "content": SYSTEM_PROMPT},
                            {"role": "user", "content": reason},
                        ],
                        "format": RESPONSE_FORMAT,
                    },
                )

            if response.status_code >= 400:
                logger.warning("Refund reason classification failed, using fallback: status=%s", response.status_code)
                return FALLBACK_CLASSIFICATION

            body = response.json()
            content = body.get("message", {}).get("content")
            if not content:
                return FALLBACK_CLASSIFICATION

            parsed = json.loads(content)
            category = parsed.get("category")
            if category not in CATEGORIES:
                return FALLBACK_CLASSIFICATION

            fraud_risk_score = min(1.0, max(0.0, float(parsed.get("fraudRiskScore", 0.0))))
            return RefundReasonClassification(
                category=RefundReasonCategory(category), fraud_risk_score=fraud_risk_score
            )
        except Exception:  # noqa: BLE001 - a classification failure must never block a refund request
            # A classification failure is a technical-infrastructure concern, not a domain
            # error — it must never block a refund request. Swallow it here at the boundary
            # and fall back.
            logger.warning("Refund reason classification failed, using fallback", exc_info=True)
            return FALLBACK_CLASSIFICATION
