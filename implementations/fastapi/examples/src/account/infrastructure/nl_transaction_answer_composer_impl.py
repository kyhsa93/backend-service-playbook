from __future__ import annotations

import logging

import httpx

from ...config.llm_config import get_llm_model, get_ollama_base_url
from ..application.query.result import TransactionSummary
from ..application.service.nl_transaction_answer_composer import NlTransactionAnswerComposer

logger = logging.getLogger(__name__)

# Live-tested against a Korean question ("이번 달에 얼마 입금했어?"): the retrieval/filtering and the
# factual content of the answer were correct, but qwen2.5:1.5b answered in English despite the
# explicit language-matching instruction below — a known limitation of this small model, not of
# the pipeline. A larger model would likely follow it more reliably; left as English-leaning
# behavior rather than adding translation post-processing, which is out of scope for this example.
SYSTEM_PROMPT = (
    "You answer a user's question about their own bank account transactions using ONLY the transaction data "
    "listed below — never mention or infer a transaction that isn't in that list. Concisely (2-3 sentences). "
    "If the listed data doesn't contain enough information to answer (e.g. it's empty), say so plainly "
    "instead of guessing.\n"
    "IMPORTANT: detect the language the question itself is written in, and write your entire answer in that "
    "same language — e.g. a Korean question always gets a Korean answer, an English question always gets an "
    "English answer, regardless of what language this instruction or the transaction data is in."
)


def _format_transactions(transactions: list[TransactionSummary]) -> str:
    if not transactions:
        return "(no matching transactions)"
    return "\n".join(
        f"- {t.type} {t.amount.amount} {t.amount.currency} on {t.created_at.date().isoformat()}" for t in transactions
    )


# A plain, non-blocking fallback used whenever the LLM call fails — describes the same data a
# working call would have been grounded in, just without natural-language phrasing.
def _fallback_answer(transactions: list[TransactionSummary]) -> str:
    if not transactions:
        return "No matching transactions were found."
    return f"Found {len(transactions)} matching transaction(s):\n{_format_transactions(transactions)}"


# A Technical Service (see root docs/architecture/domain-service.md) generating a natural-
# language answer from already-retrieved transaction records — the "Generate" half of a
# structured-data RAG pipeline (NlTransactionQueryTranslator is the "Retrieve"-preparation
# half). Uses the same self-hosted Ollama setup, without a JSON-schema-constrained response
# since the output here is free-form prose, not a structured value the caller parses.
class NlTransactionAnswerComposerImpl(NlTransactionAnswerComposer):
    async def compose(self, question: str, transactions: list[TransactionSummary]) -> str:
        try:
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{get_ollama_base_url()}/api/chat",
                    json={
                        "model": get_llm_model(),
                        "stream": False,
                        "messages": [
                            {"role": "system", "content": SYSTEM_PROMPT},
                            {
                                "role": "user",
                                "content": (
                                    f"Question: {question}\n\nTransactions:\n{_format_transactions(transactions)}"
                                ),
                            },
                        ],
                    },
                )

            if response.status_code >= 400:
                logger.warning("Answer composition failed, using fallback: status=%s", response.status_code)
                return _fallback_answer(transactions)

            body = response.json()
            content = body.get("message", {}).get("content")
            return content.strip() if content and content.strip() else _fallback_answer(transactions)
        except Exception:  # noqa: BLE001 - a composition failure must never block getting *an* answer
            # A composition failure is a technical-infrastructure concern, not a domain error —
            # it must never block the question from getting *an* answer, even a plain one.
            logger.warning("Answer composition failed, using fallback", exc_info=True)
            return _fallback_answer(transactions)
