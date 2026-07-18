// Card BC를 동기 조회하기 위한 Adapter 인터페이스 (Anticorruption Layer).
// 결제 시 카드가 존재하고 활성 상태인지, 연결된 accountId가 무엇인지를 현재 요청 안에서
// 즉시 확인해야 하므로 동기 Adapter 패턴을 사용한다(cross-domain-communication.md 참조).
// Card BC가 이미 Account를 이 방식으로 조회하고 있는 것과 동일한 패턴을 Payment가
// 재사용한다 — 반환 타입은 Card BC의 CardStatus enum을 노출하지 않고 Payment BC가
// 필요로 하는 최소 형태로 번역한다. 실제 번역은 infrastructure/card-adapter-impl.ts.
export abstract class CardAdapter {
  abstract findCard(query: {
    readonly cardId: string
    readonly ownerId: string
  }): Promise<{ cardId: string; accountId: string; active: boolean } | null>
}
