"""A deterministic fake Ollama for the e2e suite, mounted with respx.

conftest.py points OLLAMA_BASE_URL at FAKE_OLLAMA_BASE_URL and installs the router built here
for the whole test session, so every LLM Technical Service (the translator/composer in
src/account, the categorizer in src/account, the refund-reason classifier in src/payment)
exercises its real POST /api/chat request/response-parse path against deterministic content —
instead of silently taking the graceful-fallback branch because nothing listens on the default
base URL. The routing is content-based and stateless: the system message identifies WHICH
service is calling (each service has a distinctive system prompt), and the user message picks
WHAT deterministic content to answer with — no per-test mutable state, so it stays valid for
the background OutboxConsumer's asynchronous LLM calls too.

Only the fake Ollama origin is intercepted — every other httpx request (there is none today
besides the ASGI-transport test client, which respx leaves alone anyway) falls through to the
catch-all pass_through route, so testcontainers/LocalStack traffic is never swallowed.
"""

from __future__ import annotations

import json

import httpx
import respx

# The fixed origin conftest.py sets OLLAMA_BASE_URL to — a reserved .test domain, so even a
# misconfigured pass-through could never reach a real host.
FAKE_OLLAMA_BASE_URL = "http://fake-ollama.test:11434"

# Makes the fake answer 500 whenever it appears anywhere in a request's user message. Tests
# that need an LLM Technical Service's graceful-fallback path embed it in the natural input
# that reaches the prompt (question/merchant_name/reason), so the forced outage is scoped to
# exactly that request — every other request in the suite keeps getting deterministic
# successful responses.
FORCE_LLM_FAILURE_MARKER = "force-llm-500"

# Marks answers produced by the fake composer below — asserting on it proves the
# /transactions/ask answer really came through the LLM request/parse path (the composer's own
# fallback answer starts with "Found ... matching transaction(s)" instead).
FAKE_ANSWER_PREFIX = "FAKE-OLLAMA GROUNDED ANSWER:\n"


def _route_content(system_content: str, user_content: str) -> str | None:
    """Picks the deterministic response content for one chat request.

    Each branch matches a distinctive phrase from the real system prompt built in the
    corresponding infrastructure impl, and returns content in exactly the shape that service
    parses (structured JSON for the translator/categorizer/classifier, free-form prose for
    the composer). Returns None for an unrecognized system prompt.
    """
    lowered_user = user_content.lower()

    # NlTransactionQueryTranslatorImpl — _build_system_prompt().
    if "translate a user's natural-language question" in system_content:
        if "deposit" in lowered_user:
            return json.dumps({"type": "DEPOSIT", "fromDate": "", "toDate": ""})
        return json.dumps({"type": "ANY", "fromDate": "", "toDate": ""})

    # NlTransactionAnswerComposerImpl — SYSTEM_PROMPT. Echo the grounding data back so the
    # caller can assert the retrieved transactions really reached the prompt (and
    # non-matching ones did not).
    if "answer a user's question about their own bank account transactions" in system_content:
        grounding = user_content.split("Transactions:\n", 1)[-1]
        return FAKE_ANSWER_PREFIX + grounding

    # TransactionAutoCategorizerImpl — _build_system_prompt(). User content is
    # "Merchant: <name>\nAmount: <n>".
    if "classify a bank withdrawal" in system_content:
        if "starbucks" in lowered_user:
            return json.dumps({"category": "FOOD"})
        return json.dumps({"category": "OTHER"})

    # RefundReasonClassifierImpl — _build_system_prompt(). User content is the refund's
    # stated reason verbatim.
    if "classify a customer's stated refund reason" in system_content:
        if "arrived broken" in lowered_user:
            return json.dumps({"category": "DEFECTIVE_PRODUCT"})
        if "changed my mind" in lowered_user:
            return json.dumps({"category": "CHANGED_MIND"})
        return json.dumps({"category": "OTHER"})

    return None


def _handle_chat(request: httpx.Request) -> httpx.Response:
    payload = json.loads(request.content)
    system_content = ""
    user_content = ""
    for message in payload.get("messages", []):
        if message.get("role") == "system":
            system_content = message.get("content", "")
        elif message.get("role") == "user":
            user_content = message.get("content", "")

    if FORCE_LLM_FAILURE_MARKER in user_content:
        return httpx.Response(500, text="forced failure for fallback-path coverage")

    content = _route_content(system_content, user_content)
    if content is None:
        # An unrecognized system prompt means a new LLM Technical Service was added without
        # teaching this fake about it — fail loudly (the caller's fallback assertion will
        # surface it).
        return httpx.Response(500, text="fake Ollama does not recognize this system prompt")

    return httpx.Response(200, json={"message": {"role": "assistant", "content": content}})


def build_fake_ollama_router() -> respx.MockRouter:
    """Builds the respx router the session-wide `fake_ollama` fixture (conftest.py) starts.

    Only POST <FAKE_OLLAMA_BASE_URL>/api/chat — the one Ollama endpoint every LLM Technical
    Service calls, non-streaming — is mocked; the trailing catch-all `pass_through()` lets
    any other httpx request reach its real transport untouched.
    """
    router = respx.mock(assert_all_called=False)
    router.post(f"{FAKE_OLLAMA_BASE_URL}/api/chat").mock(side_effect=_handle_chat)
    router.route().pass_through()
    return router
