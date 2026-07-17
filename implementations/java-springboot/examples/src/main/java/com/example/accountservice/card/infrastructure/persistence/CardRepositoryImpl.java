package com.example.accountservice.card.infrastructure.persistence;

import com.example.accountservice.card.application.query.CardQuery;
import com.example.accountservice.card.domain.Card;
import com.example.accountservice.card.domain.CardRepository;
import com.example.accountservice.card.domain.CardStatus;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Card의 쓰기용 {@link CardRepository}와 읽기용 {@link CardQuery}를 한 클래스에서 구현한다
 * (account/infrastructure/persistence/AccountRepositoryImpl과 동일한 구조). 각 Application 레이어는 자신에게 필요한
 * 좁은 인터페이스(Repository 또는 Query)만 주입받는다.
 */
@Repository
@RequiredArgsConstructor
public class CardRepositoryImpl implements CardRepository, CardQuery {

    private final CardJpaRepository jpaRepository;

    @Override
    @Transactional
    public void save(Card card) {
        CardJpaEntity entity =
                jpaRepository
                        .findByCardId(card.getCardId())
                        .map(existing -> CardMapper.updateEntity(existing, card))
                        .orElseGet(() -> CardMapper.toNewEntity(card));
        jpaRepository.save(entity);
    }

    @Override
    public List<Card> findByAccountIdAndStatusIn(String accountId, List<CardStatus> statuses) {
        return jpaRepository
                .findByAccountIdAndStatusInOrderByCardIdDesc(accountId, statuses)
                .stream()
                .map(CardMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<Card> findByCardIdAndOwnerId(String cardId, String ownerId) {
        return jpaRepository.findByCardIdAndOwnerId(cardId, ownerId).map(CardMapper::toDomain);
    }
}
