import { generateId } from '@/common/generate-id'
import { CardStatus } from '@/card/card-enum'
import { CardErrorMessage } from '@/card/card-error-message'

export class Card {
  public readonly cardId: string
  public readonly accountId: string
  public readonly ownerId: string
  public readonly brand: string
  public readonly createdAt: Date
  private _status: CardStatus

  constructor(params: {
    cardId?: string
    accountId: string
    ownerId: string
    brand: string
    status: CardStatus
    createdAt?: Date
  }) {
    this.cardId = params.cardId ?? generateId()
    this.accountId = params.accountId
    this.ownerId = params.ownerId
    this.brand = params.brand
    this._status = params.status
    this.createdAt = params.createdAt ?? new Date()
  }

  get status(): CardStatus { return this._status }

  // The Card Aggregate has no way of knowing whether the linked account is active — the
  // Application layer judges issuability (account status) by synchronously querying via AccountAdapter (ACL) before calling this factory.
  public static issue(params: { accountId: string; ownerId: string; brand: string }): Card {
    return new Card({
      accountId: params.accountId,
      ownerId: params.ownerId,
      brand: params.brand,
      status: CardStatus.ACTIVE
    })
  }

  public suspend(): void {
    if (this._status === CardStatus.CANCELLED) throw new Error(CardErrorMessage['A cancelled card cannot be suspended.'])
    if (this._status === CardStatus.SUSPENDED) throw new Error(CardErrorMessage['The card is already suspended.'])
    this._status = CardStatus.SUSPENDED
  }

  public cancel(): void {
    if (this._status === CardStatus.CANCELLED) throw new Error(CardErrorMessage['The card is already cancelled.'])
    this._status = CardStatus.CANCELLED
  }
}
