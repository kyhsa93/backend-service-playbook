package com.example.accountservice.card.infrastructure.persistence;

import com.example.accountservice.card.domain.CardStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardJpaRepository extends JpaRepository<CardJpaEntity, Long> {
    Optional<CardJpaEntity> findByCardId(String cardId);

    Optional<CardJpaEntity> findByCardIdAndOwnerId(String cardId, String ownerId);

    List<CardJpaEntity> findByAccountIdAndStatusInOrderByCardIdDesc(
            String accountId, List<CardStatus> statuses);
}
