// The Adapter interface (an Anticorruption Layer) for synchronously querying the Card BC.
// The synchronous Adapter pattern is used since, at payment time, whether the card exists and
// is active, and what its linked accountId is, must be confirmed immediately within the
// current request (see cross-domain-communication.md). Payment reuses the same pattern Card
// BC already uses to query Account — the return type translates it into the minimal shape
// Payment BC needs, without exposing Card BC's CardStatus enum. The actual translation happens
// in infrastructure/card-adapter-impl.ts.
export abstract class CardAdapter {
  abstract findCard(query: {
    readonly cardId: string
    readonly ownerId: string
  }): Promise<{ cardId: string; accountId: string; active: boolean } | null>

  // A batch-only query for the payment.send-card-statements Task to get its target set (every
  // ACTIVE card) to generate statements for. Unlike findCard(), it has no specific ownerId scope.
  abstract findActiveCards(): Promise<{ cardId: string; accountId: string; ownerId: string }[]>
}
