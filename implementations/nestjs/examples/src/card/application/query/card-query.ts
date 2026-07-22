import { GetCardResult } from '@/card/application/query/card-result'

export abstract class CardQuery {
  abstract getCard(param: { cardId: string; ownerId: string }): Promise<GetCardResult>

  // A query for the payment.send-card-statements Task to iterate over "every ACTIVE card" as
  // a system batch — unlike getCard(), this is a batch-only method with no specific-requester
  // (ownerId) scope. It's never exposed over HTTP; only PaymentModule's CardAdapter (ACL) calls it.
  abstract getActiveCards(): Promise<{ cardId: string; accountId: string; ownerId: string }[]>
}
