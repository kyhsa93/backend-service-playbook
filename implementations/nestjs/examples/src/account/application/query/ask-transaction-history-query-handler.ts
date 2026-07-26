import { IQueryHandler, QueryHandler } from '@nestjs/cqrs'

import { AccountQuery } from '@/account/application/query/account-query'
import { AskTransactionHistoryResult } from '@/account/application/query/account-result'
import { AskTransactionHistoryQuery } from '@/account/application/query/ask-transaction-history-query'
import { NlTransactionAnswerComposer } from '@/account/application/service/nl-transaction-answer-composer'
import { NlTransactionQueryTranslator } from '@/account/application/service/nl-transaction-query-translator'

// The number of most-relevant transactions retrieved to ground the generated answer in. A
// question about "this month" or "last week" is expected to narrow well below this via the
// translated date filter; this cap just bounds worst case (e.g. an unfiltered "show me
// everything") so the composer's prompt stays a reasonable size.
const MAX_TRANSACTIONS_FOR_ANSWER = 50

// A structured-data RAG pipeline, orchestrated in the Application layer (never in the
// Controller, which only wraps the HTTP request into this Query and dispatches it):
//   1. Retrieve-preparation — NlTransactionQueryTranslator (LLM) turns the free-text question
//      into a structured filter (type/date range).
//   2. Retrieve — AccountQuery.getTransactions runs that filter, scoped to the account.
//   3. Generate — NlTransactionAnswerComposer (LLM) answers the question, grounded only in the
//      retrieved records.
//
// Security-critical: the translated filter may only narrow WHAT is returned. WHO it belongs to
// is never taken from it — `ownerId` always comes from `query.requesterId` (the authenticated
// caller, set by the Controller from UserContextStore), never from the LLM's output. This is
// the lesson the previous LLM-based refund feature in this repo got wrong in the other
// direction: it let an LLM's read of untrusted free text influence a security-relevant judgment.
// Here, the LLM only affects which of the requester's OWN transactions are shown — worst case
// on a bad translation is an inaccurate answer about the requester's own data, never someone
// else's data or unauthorized access.
@QueryHandler(AskTransactionHistoryQuery)
export class AskTransactionHistoryQueryHandler implements IQueryHandler<AskTransactionHistoryQuery> {
  constructor(
    private readonly accountQuery: AccountQuery,
    private readonly translator: NlTransactionQueryTranslator,
    private readonly composer: NlTransactionAnswerComposer
  ) {}

  public async execute(query: AskTransactionHistoryQuery): Promise<AskTransactionHistoryResult> {
    const filter = await this.translator.translate(query.question)

    const { transactions, count } = await this.accountQuery.getTransactions({
      accountId: query.accountId,
      ownerId: query.requesterId,
      type: filter.type,
      fromDate: filter.fromDate,
      toDate: filter.toDate,
      take: MAX_TRANSACTIONS_FOR_ANSWER,
      page: 0
    })

    const answer = await this.composer.compose(query.question, transactions)
    return { answer, matchedCount: count }
  }
}
