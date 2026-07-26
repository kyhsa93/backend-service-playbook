from __future__ import annotations

import json
import logging
from datetime import date

import httpx

from ...config.llm_config import get_llm_model, get_ollama_base_url
from ..application.service.nl_transaction_query_translator import NlTransactionQueryTranslator, TransactionFilter
from ..domain.transaction import TransactionType

logger = logging.getLogger(__name__)

TYPES: list[TransactionType] = ["DEPOSIT", "WITHDRAWAL", "INTEREST"]

# An empty filter degrades gracefully to "no narrowing" — the Query Handler still runs
# find_transactions with no type/date constraint, so a translation failure never blocks the
# question from being answered against the requester's most recent transactions.
FALLBACK_FILTER = TransactionFilter()

RESPONSE_FORMAT = {
    "type": "object",
    "properties": {
        "type": {"type": "string", "enum": [*TYPES, "ANY"]},
        "fromDate": {"type": "string"},
        "toDate": {"type": "string"},
    },
    "required": ["type", "fromDate", "toDate"],
    "additionalProperties": False,
}


def _build_system_prompt() -> str:
    today = date.today().isoformat()
    return (
        "You translate a user's natural-language question about their own bank account transaction history "
        f"into a structured JSON filter. Today's date is {today}. Resolve any relative date expression "
        '("this month", "last week") against that date.\n'
        'Fields: "type" — DEPOSIT, WITHDRAWAL, INTEREST, or ANY if the question doesn\'t ask about a specific '
        'type. "fromDate"/"toDate" — an ISO 8601 date (YYYY-MM-DD), or an empty string if the question implies '
        "no date boundary on that side.\n"
        "Only extract constraints the question actually states or clearly implies. Never invent a date range "
        "or transaction type the question doesn't support. Respond only through the given schema."
    )


def _parse_iso_date(value: str | None) -> date | None:
    if not value:
        return None
    try:
        return date.fromisoformat(value)
    except ValueError:
        return None


# A Technical Service (see root docs/architecture/domain-service.md) wrapping a self-hosted LLM
# call — the "Retrieve"-preparation half of a structured-data RAG pipeline
# (NlTransactionAnswerComposer is the "Generate" half). Talks to Ollama's native /api/chat
# endpoint over plain HTTP (Ollama has no official Python client), the same self-hosted
# qwen2.5:1.5b setup used elsewhere in this repo for LLM Technical Services.
class NlTransactionQueryTranslatorImpl(NlTransactionQueryTranslator):
    async def translate(self, question: str) -> TransactionFilter:
        try:
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{get_ollama_base_url()}/api/chat",
                    json={
                        "model": get_llm_model(),
                        "stream": False,
                        "messages": [
                            {"role": "system", "content": _build_system_prompt()},
                            {"role": "user", "content": question},
                        ],
                        "format": RESPONSE_FORMAT,
                    },
                )

            if response.status_code >= 400:
                logger.warning("Transaction query translation failed, using no filter: status=%s", response.status_code)
                return FALLBACK_FILTER

            body = response.json()
            content = body.get("message", {}).get("content")
            if not content:
                return FALLBACK_FILTER

            parsed = json.loads(content)
            type_value = parsed.get("type")
            return TransactionFilter(
                type=type_value if type_value in TYPES else None,
                from_date=_parse_iso_date(parsed.get("fromDate")),
                to_date=_parse_iso_date(parsed.get("toDate")),
            )
        except Exception:  # noqa: BLE001 - a translation failure must never block the question from being answered
            # A translation failure is a technical-infrastructure concern, not a domain error —
            # it must never block the question from being answered. Swallow it here at the
            # boundary and fall back.
            logger.warning("Transaction query translation failed, using no filter", exc_info=True)
            return FALLBACK_FILTER
