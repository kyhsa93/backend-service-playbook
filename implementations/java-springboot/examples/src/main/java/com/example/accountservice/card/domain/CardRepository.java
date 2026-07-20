package com.example.accountservice.card.domain;

/**
 * Card Aggregate의 쓰기용 Repository 계약(domain 소유). 읽기 전용 조회는 별도의 application/query/CardQuery 인터페이스로
 * 분리한다(cqrs-pattern.md 참고) — 다만 Command 유스케이스(카드 정지/해지 대상 조회, 단건 소유권 검증)도 조회가 필요하므로 {@code
 * findCards}는 두 인터페이스에 동일한 시그니처로 존재한다(payment/domain/PaymentRepository와 동일한 패턴).
 */
public interface CardRepository {
    void saveCard(Card card);

    CardsWithCount findCards(CardFindQuery query);
}
