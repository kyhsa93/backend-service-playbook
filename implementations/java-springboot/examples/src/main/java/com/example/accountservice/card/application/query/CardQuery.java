package com.example.accountservice.card.application.query;

import com.example.accountservice.card.domain.Card;

import java.util.Optional;

/**
 * Query Service 전용 읽기 인터페이스. 쓰기용 {@code CardRepository}(domain)와 분리된 좁은 계약이다.
 * Query Service는 이 인터페이스만 의존해야 한다 — 쓰기 메서드를 노출하지 않는다(cqrs-pattern.md 참고).
 */
public interface CardQuery {
    Optional<Card> findByCardIdAndOwnerId(String cardId, String ownerId);
}
