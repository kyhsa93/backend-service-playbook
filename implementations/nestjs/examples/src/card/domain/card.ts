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

  // 연결 계좌의 활성 여부는 Card Aggregate가 알 수 없다 — 발급 가능 여부(계좌 상태)는
  // Application 레이어가 AccountAdapter(ACL)로 동기 조회해 판단한 뒤 이 팩토리를 호출한다.
  public static issue(params: { accountId: string; ownerId: string; brand: string }): Card {
    return new Card({
      accountId: params.accountId,
      ownerId: params.ownerId,
      brand: params.brand,
      status: CardStatus.ACTIVE
    })
  }

  public suspend(): void {
    if (this._status === CardStatus.CANCELLED) throw new Error(CardErrorMessage['해지된 카드는 정지할 수 없습니다.'])
    if (this._status === CardStatus.SUSPENDED) throw new Error(CardErrorMessage['이미 정지된 카드입니다.'])
    this._status = CardStatus.SUSPENDED
  }

  public cancel(): void {
    if (this._status === CardStatus.CANCELLED) throw new Error(CardErrorMessage['이미 해지된 카드입니다.'])
    this._status = CardStatus.CANCELLED
  }
}
