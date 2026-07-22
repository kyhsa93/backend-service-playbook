// The Adapter interface (an Anticorruption Layer) for synchronously querying the Account BC.
// The synchronous Adapter pattern is used since payment eligibility (account active status +
// sufficient balance) must be confirmed immediately within the current request. The actual
// debit isn't this synchronous lookup's job — Account BC subscribes to the
// payment.completed.v1 Integration Event and performs it asynchronously (the principle from
// cross-domain-communication.md: "synchronous = query, asynchronous Integration Event = state change").
export abstract class AccountAdapter {
  abstract findAccount(query: {
    readonly accountId: string
    readonly ownerId: string
  }): Promise<{ accountId: string; active: boolean; balanceAmount: number; currency: string; email: string } | null>
}
