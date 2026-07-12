package com.example.accountservice.card.domain;

import java.util.List;

/**
 * Card Aggregate의 쓰기용 Repository 계약(domain 소유). 읽기 전용 조회는 별도의
 * application/query/CardQuery 인터페이스로 분리한다(cqrs-pattern.md 참고).
 */
public interface CardRepository {
    void save(Card card);
    List<Card> findByAccountIdAndStatusIn(String accountId, List<CardStatus> statuses);
}
