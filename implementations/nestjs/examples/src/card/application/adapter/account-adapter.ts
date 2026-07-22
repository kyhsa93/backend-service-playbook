// The Adapter interface (an Anticorruption Layer) for synchronously querying the Account BC.
// Since card issuance must immediately confirm the linked account's existence·active status
// within the current request, the synchronous Adapter pattern is used
// (see cross-domain-communication.md).
//
// The return type translates it into the minimal shape Card BC needs (active: boolean)
// instead of exposing Account BC's AccountStatus enum — preventing an upstream (Account)
// model change from leaking into the Card domain is exactly the ACL's purpose. The actual
// translation happens in infrastructure/account-adapter-impl.ts.
export abstract class AccountAdapter {
  abstract findAccount(query: {
    readonly accountId: string
    readonly ownerId: string
  }): Promise<{ accountId: string; active: boolean } | null>
}
