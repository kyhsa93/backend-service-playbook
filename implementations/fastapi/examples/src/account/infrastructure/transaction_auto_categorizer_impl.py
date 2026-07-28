from __future__ import annotations

import json
import logging

import httpx

from ...config.llm_config import get_llm_model, get_ollama_base_url
from ..application.service.transaction_auto_categorizer import TransactionAutoCategorizer
from ..domain.transaction import TransactionCategory

CATEGORIES: list[TransactionCategory] = [
    "FOOD",
    "TRANSPORT",
    "SHOPPING",
    "HOUSING",
    "MEDICAL",
    "ENTERTAINMENT",
    "UTILITIES",
    "OTHER",
]

logger = logging.getLogger(__name__)

# A classification failure (the LLM call itself, or an out-of-taxonomy answer) is a
# technical-infrastructure concern, not a domain error — this is a best-effort enrichment, not
# a financial-correctness concern, so it degrades to OTHER rather than ever blocking or
# retrying indefinitely. The same posture as NlTransactionQueryTranslatorImpl falling back to
# no filter.
FALLBACK_CATEGORY: TransactionCategory = "OTHER"

RESPONSE_FORMAT = {
    "type": "object",
    "properties": {"category": {"type": "string", "enum": CATEGORIES}},
    "required": ["category"],
    "additionalProperties": False,
}


def _build_system_prompt() -> str:
    return (
        "You classify a bank withdrawal into exactly one spending category based on its payee/merchant name and "
        f"amount. Categories: {', '.join(CATEGORIES)}. Use OTHER only when none of the other categories "
        "plausibly fit. Respond only through the given schema."
    )


# A Technical Service (see root docs/architecture/domain-service.md) wrapping a self-hosted LLM
# call — the same self-hosted qwen2.5:1.5b Ollama setup as NlTransactionQueryTranslatorImpl/
# NlTransactionAnswerComposerImpl, just a different prompt/schema for a different job
# (classification instead of query translation or answer generation).
class TransactionAutoCategorizerImpl(TransactionAutoCategorizer):
    async def categorize(self, merchant_name: str, amount: int) -> TransactionCategory:
        try:
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{get_ollama_base_url()}/api/chat",
                    json={
                        "model": get_llm_model(),
                        "stream": False,
                        "messages": [
                            {"role": "system", "content": _build_system_prompt()},
                            {"role": "user", "content": f"Merchant: {merchant_name}\nAmount: {amount}"},
                        ],
                        "format": RESPONSE_FORMAT,
                    },
                )

            if response.status_code >= 400:
                logger.warning(
                    "Transaction categorization failed, using fallback category: status=%s", response.status_code
                )
                return FALLBACK_CATEGORY

            body = response.json()
            content = body.get("message", {}).get("content")
            if not content:
                return FALLBACK_CATEGORY

            parsed = json.loads(content)
            category = parsed.get("category")
            return category if category in CATEGORIES else FALLBACK_CATEGORY
        except Exception:  # noqa: BLE001 - a classification failure must never block the withdrawal pipeline
            # A classification failure is a technical-infrastructure concern, not a domain
            # error — it must never block or fail the async pipeline. Swallow it here at the
            # boundary and fall back.
            logger.warning("Transaction categorization failed, using fallback category", exc_info=True)
            return FALLBACK_CATEGORY
