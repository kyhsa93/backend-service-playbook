import { GetCardResult } from '@/card/application/query/card-result'

export abstract class CardQuery {
  abstract getCard(param: { cardId: string; ownerId: string }): Promise<GetCardResult>

  // payment.send-card-statements Task가 "모든 ACTIVE 카드"를 시스템 배치로 순회하기
  // 위한 조회다 — getCard()와 달리 특정 요청자(ownerId) 스코프가 없는 배치 전용
  // 메서드다. HTTP로 노출하지 않고 PaymentModule의 CardAdapter(ACL)만 호출한다.
  abstract getActiveCards(): Promise<{ cardId: string; accountId: string; ownerId: string }[]>
}
