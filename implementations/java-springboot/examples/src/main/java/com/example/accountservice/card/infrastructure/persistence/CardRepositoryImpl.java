package com.example.accountservice.card.infrastructure.persistence;

import com.example.accountservice.card.application.query.CardQuery;
import com.example.accountservice.card.domain.Card;
import com.example.accountservice.card.domain.CardFindQuery;
import com.example.accountservice.card.domain.CardRepository;
import com.example.accountservice.card.domain.CardsWithCount;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.List;
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
    private final EntityManager em;

    @Override
    @Transactional
    public void saveCard(Card card) {
        CardJpaEntity entity =
                jpaRepository
                        .findByCardId(card.getCardId())
                        .map(existing -> CardMapper.updateEntity(existing, card))
                        .orElseGet(() -> CardMapper.toNewEntity(card));
        jpaRepository.save(entity);
    }

    @Override
    public CardsWithCount findCards(CardFindQuery query) {
        String listJpql = buildJpql(query, false);
        var listQuery =
                em.createQuery(listJpql, CardJpaEntity.class)
                        .setFirstResult(query.page() * query.take())
                        .setMaxResults(query.take());
        applyParams(listQuery, query);
        List<Card> cards = listQuery.getResultList().stream().map(CardMapper::toDomain).toList();

        String countJpql = buildJpql(query, true);
        var countQuery = em.createQuery(countJpql, Long.class);
        applyParams(countQuery, query);
        long count = countQuery.getSingleResult();

        return new CardsWithCount(cards, count);
    }

    private String buildJpql(CardFindQuery query, boolean count) {
        StringBuilder sb =
                new StringBuilder(
                        count
                                ? "SELECT COUNT(c) FROM CardJpaEntity c"
                                : "SELECT c FROM CardJpaEntity c");
        List<String> conditions = new ArrayList<>();
        if (query.cardId() != null && !query.cardId().isBlank()) {
            conditions.add("c.cardId = :cardId");
        }
        if (query.ownerId() != null && !query.ownerId().isBlank()) {
            conditions.add("c.ownerId = :ownerId");
        }
        if (query.accountId() != null && !query.accountId().isBlank()) {
            conditions.add("c.accountId = :accountId");
        }
        if (query.statuses() != null && !query.statuses().isEmpty()) {
            conditions.add("c.status IN :statuses");
        }
        if (!conditions.isEmpty()) {
            sb.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        if (!count) {
            sb.append(" ORDER BY c.cardId DESC");
        }
        return sb.toString();
    }

    private void applyParams(Query q, CardFindQuery query) {
        if (query.cardId() != null && !query.cardId().isBlank()) {
            q.setParameter("cardId", query.cardId());
        }
        if (query.ownerId() != null && !query.ownerId().isBlank()) {
            q.setParameter("ownerId", query.ownerId());
        }
        if (query.accountId() != null && !query.accountId().isBlank()) {
            q.setParameter("accountId", query.accountId());
        }
        if (query.statuses() != null && !query.statuses().isEmpty()) {
            q.setParameter("statuses", query.statuses());
        }
    }
}
