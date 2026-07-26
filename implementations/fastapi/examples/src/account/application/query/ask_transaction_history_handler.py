from dataclasses import dataclass

from ...domain.errors import AccountNotFoundError
from ...domain.repository import AccountQuery
from ..service.nl_transaction_answer_composer import NlTransactionAnswerComposer
from ..service.nl_transaction_query_translator import NlTransactionQueryTranslator
from .result import AskTransactionHistoryResult, MoneyResult, TransactionSummary

# The number of most-relevant transactions retrieved to ground the generated answer in. A
# question about "this month" or "last week" is expected to narrow well below this via the
# translated date filter; this cap just bounds worst case (e.g. an unfiltered "show me
# everything") so the composer's prompt stays a reasonable size.
MAX_TRANSACTIONS_FOR_ANSWER = 50


@dataclass
class AskTransactionHistoryQuery:
    account_id: str
    requester_id: str
    question: str


# A structured-data RAG pipeline, orchestrated in the Application layer (never in the Router,
# which only wraps the HTTP request into this Query and dispatches it):
#   1. Retrieve-preparation — NlTransactionQueryTranslator (LLM) turns the free-text question
#      into a structured filter (type/date range).
#   2. Retrieve — AccountQuery.find_transactions runs that filter, scoped to the account.
#   3. Generate — NlTransactionAnswerComposer (LLM) answers the question, grounded only in the
#      retrieved records.
#
# Security-critical: the translated filter may only narrow WHAT is returned. WHO it belongs to
# is never taken from it — the account lookup (and therefore the transactions it scopes) is
# always scoped by `query.requester_id` (the authenticated caller, set by the Router from
# `current_user`), never from the LLM's output. This is the lesson the previous LLM-based
# refund feature in this repo got wrong in the other direction: it let an LLM's read of
# untrusted free text influence a security-relevant judgment. Here, the LLM only affects which
# of the requester's OWN transactions are shown — worst case on a bad translation is an
# inaccurate answer about the requester's own data, never someone else's data or unauthorized
# access.
class AskTransactionHistoryHandler:
    def __init__(
        self,
        repo: AccountQuery,
        translator: NlTransactionQueryTranslator,
        composer: NlTransactionAnswerComposer,
    ) -> None:
        self._repo = repo
        self._translator = translator
        self._composer = composer

    async def execute(self, query: AskTransactionHistoryQuery) -> AskTransactionHistoryResult:
        accounts, _ = await self._repo.find_accounts(
            page=0, take=1, account_id=query.account_id, owner_id=query.requester_id
        )
        account = accounts[0] if accounts else None
        if account is None:
            raise AccountNotFoundError(query.account_id)

        filter_ = await self._translator.translate(query.question)

        transactions, count = await self._repo.find_transactions(
            query.account_id,
            page=0,
            take=MAX_TRANSACTIONS_FOR_ANSWER,
            type=filter_.type,
            from_date=filter_.from_date,
            to_date=filter_.to_date,
        )

        summaries = [
            TransactionSummary(
                transaction_id=t.transaction_id,
                type=t.type,
                amount=MoneyResult(amount=t.amount.amount, currency=t.amount.currency),
                created_at=t.created_at,
            )
            for t in transactions
        ]
        answer = await self._composer.compose(query.question, summaries)
        return AskTransactionHistoryResult(answer=answer, matched_count=count)
